package br.leno.radarcorrida;

import android.accessibilityservice.AccessibilityService;
import android.accessibilityservice.AccessibilityServiceInfo;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import br.leno.radarcorrida.core.Evaluation;
import br.leno.radarcorrida.core.Offer;
import br.leno.radarcorrida.core.OfferParser;
import br.leno.radarcorrida.core.Ranker;
import br.leno.radarcorrida.core.Settings;

/**
 * Coração do app: recebe eventos de mudança de tela, lê a oferta dos apps de corrida
 * configurados, calcula e mostra a etiqueta. Somente leitura — não clica em nada.
 *
 * Duas fontes de leitura:
 *  1. Árvore de acessibilidade (texto dos elementos da tela) — instantânea e sem custo.
 *  2. Leitura por imagem (OCR, Android 11+) — usada quando o app de corrida esconde o
 *     texto da oferta da acessibilidade. Limitada a uma captura a cada {@link #OCR_INTERVAL_MS},
 *     só enquanto o app de corrida está na tela.
 */
public class RadarService extends AccessibilityService {
    private static final long SETTLE_MS = 120;
    private static final long MIN_INTERVAL_MS = 350;
    private static final long OCR_INTERVAL_MS = 1200;
    private static final long GONE_MS_TREE = 1500;
    /** Reserva: se a tela inicial não for reconhecida, a etiqueta some depois disto sem oferta. */
    private static final long GONE_MS_OCR = 2000;
    /** Na tela inicial (sem cartão de oferta), a leitura por imagem fica mais espaçada. */
    private static final long OCR_INTERVAL_HOME_MS = 2500;
    private static final long MAX_SHOW_MS = 25000;
    private static final long TARGET_ACTIVE_MS = 4000;
    private static final int MAX_NODES = 1500;
    private static final long WINDOWS_DUMP_EVERY_MS = 10000;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> discoveredThisSession = new HashSet<>();

    private Settings settings;
    private Set<String> targets;
    private boolean debug;
    private boolean discovery;
    private boolean ocrEnabled;
    private int overlayYdp;

    private OverlayCard overlay;
    private RideLog log;
    private ScreenOcr ocr;
    private br.leno.radarcorrida.core.DayTracker day;
    private long lastDaySave;

    private String pendingPkg;
    private boolean scanScheduled;
    private long lastScanAt;
    private long lastOcrAt;
    private long lastTargetEventAt;
    private long lastWindowsDumpAt;
    private String lastSig = "";
    private long lastSeenAt;
    private boolean lastFromOcr;
    /** A barra de baixo da Uber ("Você está online"...) está visível: não há cartão de oferta. */
    private boolean homeVisible;
    /** Momento em que a tela inicial voltou (cartão de oferta sumiu). */
    private long homeSeenAt;

    private final Runnable scan = this::scanNow;
    private final Runnable hide = this::hideAndReset;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> reload();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        overlay = new OverlayCard(this);
        log = new RideLog(this);
        ocr = new ScreenOcr(this);
        day = Prefs.loadDay(this);
        reload();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void reload() {
        settings = Prefs.settings(this);
        targets = Prefs.packages(this);
        debug = Prefs.debug(this);
        discovery = Prefs.discovery(this);
        ocrEnabled = Prefs.ocr(this);
        overlayYdp = Prefs.overlayYdp(this);
        if (overlay != null) overlay.setNetMode(settings.netGoal);
        applySystemFilter();
    }

    /** Pede ao Android para só entregar eventos dos apps de corrida (exceto no modo descoberta). */
    private void applySystemFilter() {
        AccessibilityServiceInfo info = getServiceInfo();
        if (info == null) return;
        info.packageNames = (discovery || targets.isEmpty())
                ? null : targets.toArray(new String[0]);
        info.notificationTimeout = 150;
        setServiceInfo(info);
    }

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        if (event == null || event.getPackageName() == null || targets == null) return;
        String pkg = event.getPackageName().toString();

        if (targets.contains(pkg)) {
            pendingPkg = pkg;
            lastTargetEventAt = SystemClock.elapsedRealtime();
            scheduleScan(0);
        } else if (discovery && !pkg.equals(getPackageName())) {
            checkDiscovery(event, pkg);
        }
    }

    private void scheduleScan(long extraDelay) {
        if (scanScheduled) return;
        long sinceLast = SystemClock.elapsedRealtime() - lastScanAt;
        long wait = Math.max(SETTLE_MS, MIN_INTERVAL_MS - sinceLast) + extraDelay;
        scanScheduled = true;
        handler.postDelayed(scan, wait);
    }

    /** Modo descoberta: registra só o NOME do pacote que mostrou "R$" e "km". Nenhum conteúdo. */
    private void checkDiscovery(AccessibilityEvent e, String pkg) {
        if (discoveredThisSession.contains(pkg)) return;
        StringBuilder sb = new StringBuilder();
        for (CharSequence cs : e.getText()) if (cs != null) sb.append(cs).append(' ');
        if (e.getContentDescription() != null) sb.append(e.getContentDescription());
        String s = sb.toString();
        if (s.contains("R$") && s.toLowerCase().contains("km")) {
            discoveredThisSession.add(pkg);
            log.discovered(pkg);
        }
    }

    private void scanNow() {
        scanScheduled = false;
        long now = SystemClock.elapsedRealtime();
        lastScanAt = now;
        String pkg = pendingPkg;
        if (pkg == null) return;

        List<String> winInfo = new ArrayList<>();
        List<String> texts = collectTexts(pkg, winInfo);
        trackDay(texts);
        importHistory(texts);

        if (debug && !texts.isEmpty() && looksRelevant(texts)) {
            if (now - lastWindowsDumpAt > WINDOWS_DUMP_EVERY_MS) {
                lastWindowsDumpAt = now;
                List<String> withInfo = new ArrayList<>(winInfo);
                withInfo.addAll(texts);
                log.dumpScreen(pkg, withInfo);
            } else {
                log.dumpScreen(pkg, texts);
            }
        }

        // Cartão de oferta aberto, a Uber esconde a barra de baixo ("Você está online",
        // "Preço dinâmico mínimo"...). Quando ela volta, a oferta saiu: some com a etiqueta já.
        if (!texts.isEmpty()) {
            boolean home = isHomeScreen(texts);
            if (home && !homeVisible) homeSeenAt = now;
            if (home && overlay.isShowing() && lastFromOcr) hideAndReset();
            if (!home && homeVisible) lastOcrAt = 0;   // a tela mudou: pode ser oferta nova, lê já
            homeVisible = home;
        }

        // Tela que não é de oferta (histórico de ganhos): nada a avaliar.
        if (isNonOfferScreen(texts)) {
            handleNoOffer(now);
            return;
        }

        List<Offer> offers = OfferParser.parseAll(texts);
        if (!offers.isEmpty()) {
            handleOffers(pkg, offers, false);
            return;
        }

        // Motorista offline: nada a ler.
        if (isOffline(texts)) {
            handleNoOffer(now);
            return;
        }

        // A árvore não mostrou oferta: tenta a leitura por imagem, se o app de corrida está ativo.
        if (ocrEnabled && ScreenOcr.supported() && !ocr.isBusy()
                && now - lastTargetEventAt < TARGET_ACTIVE_MS && targetOnScreen()) {
            long interval = homeVisible ? OCR_INTERVAL_HOME_MS : OCR_INTERVAL_MS;
            if (now - lastOcrAt >= interval) {
                lastOcrAt = now;
                final String ocrPkg = pkg;
                final long startedAt = now;
                ocr.read(overlay.screenRect(), lines -> onOcrLines(ocrPkg, lines, startedAt));
            } else {
                // Garante nova tentativa mesmo se o app de corrida parar de gerar eventos.
                scheduleScan(interval - (now - lastOcrAt));
            }
        }
        handleNoOffer(now);
    }

    private void onOcrLines(String pkg, List<String> lines, long startedAt) {
        long now = SystemClock.elapsedRealtime();
        // Privacidade: se o motorista saiu do app de corrida enquanto a imagem era lida
        // (WhatsApp, tela de apps recentes...), descarta tudo, inclusive do diagnóstico.
        if (!targetOnScreen()) {
            handleNoOffer(now);
            return;
        }
        if (debug && !lines.isEmpty() && looksRelevant(lines)) log.dumpScreen(pkg + " [imagem]", lines);

        // Imagem capturada antes de o cartão sumir: não ressuscita a oferta antiga.
        if (startedAt < homeSeenAt && homeVisible) {
            handleNoOffer(now);
            return;
        }

        // Leitura por imagem só vale como oferta se a tela tiver cara de oferta (botão/estimativa).
        List<Offer> offers = OfferParser.hasOfferMarker(lines)
                ? OfferParser.parseAll(lines) : new ArrayList<Offer>();
        offers = stabilize(offers, now);
        if (!offers.isEmpty()) {
            handleOffers(pkg, offers, true);
        } else {
            handleNoOffer(now);
        }
        // Enquanto há oferta na tela, continua conferindo (detecta troca ou sumiço da oferta).
        if (overlay.isShowing()) scheduleScan(OCR_INTERVAL_MS);
    }

    /** Última leitura com busca e viagem lidas na tela, por valor da oferta (R$ em centavos). */
    private final java.util.Map<Long, Offer> directReads = new java.util.HashMap<>();
    private final java.util.Map<Long, Long> directReadAt = new java.util.HashMap<>();

    /**
     * A leitura por imagem varia de um quadro para outro. Se neste quadro a busca saiu estimada,
     * mas a mesma oferta (mesmo valor) foi lida por completo há pouco, usa a leitura completa:
     * evita o veredito trocar na frente do motorista.
     */
    private List<Offer> stabilize(List<Offer> offers, long now) {
        if (offers.isEmpty()) return offers;
        List<Offer> out = new ArrayList<>(offers.size());
        for (Offer o : offers) {
            long key = Math.round(o.fare * 100);
            if (!o.pickupEstimated) {
                directReads.put(key, o);
                directReadAt.put(key, now);
                out.add(o);
                continue;
            }
            Offer d = directReads.get(key);
            Long at = directReadAt.get(key);
            out.add(d != null && at != null && now - at < 60_000 ? d : o);
        }
        if (directReads.size() > 50) { directReads.clear(); directReadAt.clear(); }
        return out;
    }

    private void handleOffers(String pkg, List<Offer> offers, boolean fromOcr) {
        long now = SystemClock.elapsedRealtime();
        lastSeenAt = now;
        lastFromOcr = fromOcr;

        StringBuilder sigB = new StringBuilder();
        for (Offer o : offers) sigB.append(o.signature()).append('#');
        String sig = sigB.toString();

        if (!sig.equals(lastSig) || !overlay.isShowing()) {
            List<Evaluation> ranked = Ranker.evaluateAndRank(offers, settings);
            if (ranked.size() == 1) overlay.show(ranked.get(0), overlayYdp);
            else overlay.showMany(ranked, overlayYdp);
            if (!sig.equals(lastSig)) {
                for (int i = 0; i < ranked.size(); i++) {
                    log.appendRide(pkg + (fromOcr ? " [imagem]" : ""), ranked.get(i), i + 1, ranked.size());
                }
            }
            lastSig = sig;
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, MAX_SHOW_MS);
    }

    private void handleNoOffer(long now) {
        if (!overlay.isShowing()) return;
        long gone = lastFromOcr ? GONE_MS_OCR : GONE_MS_TREE;
        if (now - lastSeenAt > gone) {
            hideAndReset();
        } else {
            handler.removeCallbacks(hide);
            handler.postDelayed(hide, gone - (now - lastSeenAt));
        }
    }

    /** Datas das corridas do histórico já vistas nesta sessão (assinatura → data). */
    private final java.util.Map<String, String> histDates = new java.util.HashMap<>();

    /** Tela "Histórico de ganhos" aberta: registra as corridas visíveis (sem repetir). */
    private void importHistory(List<String> texts) {
        if (!br.leno.radarcorrida.core.HistoryParser.isHistoryScreen(texts)) return;
        java.util.Calendar c = java.util.Calendar.getInstance();
        List<br.leno.radarcorrida.core.HistoryParser.Trip> trips = br.leno.radarcorrida.core.HistoryParser
                .parse(texts, c.get(java.util.Calendar.YEAR), c.get(java.util.Calendar.MONTH) + 1, histDates);
        if (trips.isEmpty()) return;
        int added = log.appendHistory(trips);
        SharedPreferences sp = Prefs.sp(this);
        if (!Prefs.today().equals(sp.getString(Prefs.K_HIST_LAST, ""))) {
            sp.edit().putString(Prefs.K_HIST_LAST, Prefs.today()).apply();
        }
        if (added > 0) {
            overlay.showHint("✔ " + added + (added == 1 ? " corrida registrada" : " corridas registradas")
                    + " no Radar. Continue rolando para registrar mais.", 2500);
        }
    }

    /** Fim de sessão (ficou offline): lembra de registrar as corridas de hoje, se ainda não fez. */
    private void remindHistory() {
        String last = Prefs.sp(this).getString(Prefs.K_HIST_LAST, "");
        if (Prefs.today().equals(last)) return;
        overlay.showHint("📋 Antes de sair: abra Ganhos → Histórico de ganhos e role até as corridas "
                + "de hoje. O Radar registra sozinho e calcula seus números reais.", 12000);
    }

    /** Acumulado do dia: ganhos de hoje e tempo online, lidos da tela da Uber. */
    private void trackDay(List<String> texts) {
        if (texts.isEmpty()) return;
        boolean wasOnline = day.online;
        long wall = System.currentTimeMillis();
        boolean earnBefore = day.earnings >= 0;
        double prevEarn = day.earnings;
        boolean changed = day.onScreen(Prefs.today(), wall, texts);
        if (wasOnline && !day.online && day.earnings > 0) remindHistory();
        // Grava logo quando os ganhos mudam; o tempo online, no máximo a cada 30 s.
        if (changed && (day.earnings != prevEarn || !earnBefore || wall - lastDaySave > 30_000)) {
            lastDaySave = wall;
            Prefs.saveDay(this, day);
        }
        if (day.earnings >= 0) {
            overlay.setDayInfo(String.format(java.util.Locale.forLanguageTag("pt-BR"),
                    "HOJE R$ %.0f/%.0f", day.earnings, settings.dailyGoal));
        }
    }

    /** Telas com valores, tempo e km que NÃO são ofertas (ex.: Histórico de ganhos da Uber). */
    private static boolean isNonOfferScreen(List<String> texts) {
        for (String t : texts) {
            if (t.equalsIgnoreCase("Histórico de ganhos") || t.equalsIgnoreCase("Seletor de data")) return true;
        }
        return false;
    }

    private static final String[] HOME_BAR = {
            "Você está online", "Preço dinâmico mínimo", "Procurando viagens", "Você está offline", "Ficar online"
    };

    /** Barra de baixo da tela inicial da Uber: some enquanto o cartão de oferta está aberto. */
    static boolean isHomeScreen(List<String> texts) {
        for (String t : texts) {
            if (t == null) continue;
            for (String h : HOME_BAR) if (t.equalsIgnoreCase(h)) return true;
        }
        return false;
    }

    /** Telas da Uber de quem está offline ("Você está offline", botão "Ficar online"). */
    private static boolean isOffline(List<String> texts) {
        for (String t : texts) {
            if (t.equalsIgnoreCase("Você está offline") || t.equalsIgnoreCase("Ficar online")) return true;
        }
        return false;
    }

    /** Só grava no diagnóstico telas com cara de oferta (evita escrever a cada mudança do mapa). */
    private static boolean looksRelevant(List<String> texts) {
        for (String t : texts) {
            if (t.contains("R$") || t.contains("RS") || t.contains(" km") || t.contains("km)")) return true;
        }
        return false;
    }

    private void hideAndReset() {
        handler.removeCallbacks(hide);
        if (overlay != null) overlay.hide();
        lastSig = "";
    }

    /**
     * O app de corrida tem uma janela visível agora? A leitura por imagem só roda nesse caso:
     * nunca captura WhatsApp, galeria, banco ou a tela de apps recentes.
     */
    private boolean targetOnScreen() {
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                AccessibilityNodeInfo root = w.getRoot();
                CharSequence rp = root != null ? root.getPackageName() : null;
                if (rp != null && targets.contains(rp.toString())) return true;
            }
        } catch (RuntimeException ignored) {
            // lista de janelas mudando: na dúvida, não lê
        }
        return false;
    }

    /**
     * Textos de todas as janelas do pacote alvo. Em winInfo vai um resumo de TODAS as janelas
     * da tela (tipo, pacote, nº de textos) para o diagnóstico.
     */
    private List<String> collectTexts(String pkg, List<String> winInfo) {
        List<String> out = new ArrayList<>();
        int[] budget = {MAX_NODES};
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                CharSequence rp = root != null ? root.getPackageName() : null;
                int before = out.size();
                if (root != null && rp != null && pkg.contentEquals(rp)) walk(root, out, 0, budget);
                winInfo.add("# janela tipo=" + w.getType() + " camada=" + w.getLayer()
                        + " pacote=" + rp + " textos=" + (out.size() - before));
            }
            if (out.isEmpty()) {
                AccessibilityNodeInfo root = getRootInActiveWindow();
                if (root != null && root.getPackageName() != null
                        && pkg.contentEquals(root.getPackageName())) {
                    walk(root, out, 0, budget);
                }
            }
        } catch (RuntimeException ignored) {
            // A árvore de nós pode mudar no meio da leitura; o próximo evento tenta de novo.
        }
        if (budget[0] <= 0) winInfo.add("# limite de elementos atingido");
        return out;
    }

    private void walk(AccessibilityNodeInfo n, List<String> out, int depth, int[] budget) {
        if (n == null || depth > 60 || budget[0]-- <= 0) return;
        CharSequence text = n.getText();
        add(out, text);
        CharSequence cd = n.getContentDescription();
        if (cd != null && (text == null || !cd.toString().contentEquals(text))) add(out, cd);
        for (int i = 0; i < n.getChildCount(); i++) {
            walk(n.getChild(i), out, depth + 1, budget);
        }
    }

    private static void add(List<String> out, CharSequence cs) {
        if (cs == null) return;
        String s = cs.toString().trim();
        if (!s.isEmpty()) out.add(s);
    }

    @Override
    public void onInterrupt() {
        hideAndReset();
    }

    @Override
    public void onDestroy() {
        Prefs.sp(this).unregisterOnSharedPreferenceChangeListener(prefsListener);
        handler.removeCallbacksAndMessages(null);
        if (overlay != null) overlay.hide();
        if (ocr != null) ocr.close();
        if (day != null) Prefs.saveDay(this, day);
        super.onDestroy();
    }
}
