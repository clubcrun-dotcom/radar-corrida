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
    private static final long GONE_MS_OCR = 3500;
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

    private String pendingPkg;
    private boolean scanScheduled;
    private long lastScanAt;
    private long lastOcrAt;
    private long lastTargetEventAt;
    private long lastWindowsDumpAt;
    private String lastSig = "";
    private long lastSeenAt;
    private boolean lastFromOcr;

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

        List<Offer> offers = OfferParser.parseAll(texts);
        if (!offers.isEmpty()) {
            handleOffers(pkg, offers, false);
            return;
        }

        // A árvore não mostrou oferta: tenta a leitura por imagem, se o app de corrida está ativo.
        if (ocrEnabled && ScreenOcr.supported() && !ocr.isBusy()
                && now - lastTargetEventAt < TARGET_ACTIVE_MS) {
            if (now - lastOcrAt >= OCR_INTERVAL_MS) {
                lastOcrAt = now;
                final String ocrPkg = pkg;
                ocr.read(overlay.screenRect(), lines -> onOcrLines(ocrPkg, lines));
            } else {
                // Garante nova tentativa mesmo se o app de corrida parar de gerar eventos.
                scheduleScan(OCR_INTERVAL_MS - (now - lastOcrAt));
            }
        }
        handleNoOffer(now);
    }

    private void onOcrLines(String pkg, List<String> lines) {
        long now = SystemClock.elapsedRealtime();
        if (debug && !lines.isEmpty() && looksRelevant(lines)) log.dumpScreen(pkg + " [imagem]", lines);

        List<Offer> offers = OfferParser.parseAll(lines);
        if (!offers.isEmpty()) {
            handleOffers(pkg, offers, true);
        } else {
            handleNoOffer(now);
        }
        // Enquanto há oferta na tela, continua conferindo (detecta troca ou sumiço da oferta).
        if (overlay.isShowing()) scheduleScan(OCR_INTERVAL_MS);
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
        super.onDestroy();
    }
}
