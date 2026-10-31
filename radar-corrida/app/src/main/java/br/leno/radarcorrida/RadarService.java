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
 * Coração do app: recebe eventos de mudança de tela, lê os textos dos apps de corrida
 * configurados, calcula e mostra a etiqueta. Somente leitura — não clica em nada.
 *
 * Economia de bateria/CPU (celulares fracos):
 *  - o Android só entrega eventos dos pacotes da lista (filtro aplicado no sistema);
 *  - no máximo uma leitura de tela a cada {@link #MIN_INTERVAL_MS}, mesmo com o mapa animando;
 *  - leitura limitada a {@link #MAX_NODES} elementos; etiqueta só é redesenhada se a oferta mudar;
 *  - sem internet, sem GPS, sem processo em segundo plano além do próprio serviço.
 */
public class RadarService extends AccessibilityService {
    private static final long SETTLE_MS = 120;        // espera a tela terminar de desenhar
    private static final long MIN_INTERVAL_MS = 350;  // teto: ~3 leituras por segundo
    private static final long GONE_MS = 1500;         // oferta sumiu da tela → esconde
    private static final long MAX_SHOW_MS = 25000;    // teto de exibição
    private static final int MAX_NODES = 600;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Set<String> discoveredThisSession = new HashSet<>();

    private Settings settings;
    private Set<String> targets;
    private boolean debug;
    private boolean discovery;
    private int overlayYdp;

    private OverlayCard overlay;
    private RideLog log;

    private String pendingPkg;
    private boolean scanScheduled;
    private long lastScanAt;
    private String lastSig = "";
    private long lastSeenAt;

    private final Runnable scan = this::scanNow;
    private final Runnable hide = this::hideAndReset;

    private final SharedPreferences.OnSharedPreferenceChangeListener prefsListener =
            (sp, key) -> reload();

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        overlay = new OverlayCard(this);
        log = new RideLog(this);
        reload();
        Prefs.sp(this).registerOnSharedPreferenceChangeListener(prefsListener);
    }

    private void reload() {
        settings = Prefs.settings(this);
        targets = Prefs.packages(this);
        debug = Prefs.debug(this);
        discovery = Prefs.discovery(this);
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
            if (!scanScheduled) {
                long sinceLast = SystemClock.elapsedRealtime() - lastScanAt;
                long wait = Math.max(SETTLE_MS, MIN_INTERVAL_MS - sinceLast);
                scanScheduled = true;
                handler.postDelayed(scan, wait);
            }
        } else if (discovery && !pkg.equals(getPackageName())) {
            checkDiscovery(event, pkg);
        }
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
        lastScanAt = SystemClock.elapsedRealtime();
        String pkg = pendingPkg;
        if (pkg == null) return;

        List<String> texts = collectTexts(pkg);
        if (texts.isEmpty()) return;

        if (debug && looksRelevant(texts)) log.dumpScreen(pkg, texts);

        List<Offer> offers = OfferParser.parseAll(texts);
        long now = SystemClock.elapsedRealtime();
        if (offers.isEmpty()) {
            if (overlay.isShowing() && now - lastSeenAt > GONE_MS) hideAndReset();
            else if (overlay.isShowing()) {
                handler.removeCallbacks(hide);
                handler.postDelayed(hide, GONE_MS);
            }
            return;
        }

        lastSeenAt = now;
        StringBuilder sigB = new StringBuilder();
        for (Offer o : offers) sigB.append(o.signature()).append('#');
        String sig = sigB.toString();

        if (!sig.equals(lastSig) || !overlay.isShowing()) {
            List<Evaluation> ranked = Ranker.evaluateAndRank(offers, settings);
            if (ranked.size() == 1) overlay.show(ranked.get(0), overlayYdp);
            else overlay.showMany(ranked, overlayYdp);
            if (!sig.equals(lastSig)) {
                for (int i = 0; i < ranked.size(); i++) {
                    log.appendRide(pkg, ranked.get(i), i + 1, ranked.size());
                }
            }
            lastSig = sig;
        }
        handler.removeCallbacks(hide);
        handler.postDelayed(hide, MAX_SHOW_MS);
    }

    /** Só grava no diagnóstico telas com cara de oferta (evita escrever a cada mudança do mapa). */
    private static boolean looksRelevant(List<String> texts) {
        for (String t : texts) {
            if (t.contains("R$") || t.contains(" km") || t.contains("km)")) return true;
        }
        return false;
    }

    private void hideAndReset() {
        handler.removeCallbacks(hide);
        if (overlay != null) overlay.hide();
        lastSig = "";
    }

    /** Textos de todas as janelas do pacote alvo (o cartão da oferta costuma ser uma janela própria). */
    private List<String> collectTexts(String pkg) {
        List<String> out = new ArrayList<>();
        int[] budget = {MAX_NODES};
        try {
            List<AccessibilityWindowInfo> windows = getWindows();
            for (AccessibilityWindowInfo w : windows) {
                AccessibilityNodeInfo root = w.getRoot();
                if (root == null) continue;
                CharSequence rp = root.getPackageName();
                if (rp != null && pkg.contentEquals(rp)) walk(root, out, 0, budget);
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
        return out;
    }

    private void walk(AccessibilityNodeInfo n, List<String> out, int depth, int[] budget) {
        if (n == null || depth > 40 || budget[0]-- <= 0) return;
        if (!n.isVisibleToUser()) return;
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
        super.onDestroy();
    }
}
