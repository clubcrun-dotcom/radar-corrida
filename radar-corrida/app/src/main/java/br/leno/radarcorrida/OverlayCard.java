package br.leno.radarcorrida;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.List;
import java.util.Locale;

import br.leno.radarcorrida.core.Evaluation;

/**
 * Etiqueta desenhada por cima do app de corrida, feita para leitura em 1 segundo:
 *
 *   ┌──────────────────────────────────────────┐
 *   │ AVALIAR                      NO TRAJETO  │  ← faixa na cor do veredito
 *   ├──────────┬──────────┬──────────┬─────────┤
 *   │ R$/KM    │ LUCRO/H  │ LUCRO    │ TRÂNSITO│  ← 4 quadros, cada valor na cor
 *   │ 2,16     │ 46       │ 67%      │ 23 km/h │     da sua meta (verde/amarelo/
 *   │          │          │ R$ 11,65 │ moderado│     vermelho; branco = sem meta)
 *   ├──────────┴──────────┴──────────┴─────────┤
 *   │ ✘ FORA DA ROTA · Santa Catarina, S. Gonç. │  ← caixa da rota (verde/vermelha)
 *   │ R$ 17,48 · 8,1 km · 23 min · busca 1,1 km │
 *   └──────────────────────────────────────────┘
 *
 * Usa TYPE_ACCESSIBILITY_OVERLAY (dispensa a permissão "sobrepor a outros apps") e é
 * NOT_TOUCHABLE: toques passam direto para o app de baixo. Nunca aciona nada.
 */
final class OverlayCard {
    private static final int C_GOOD = 0xFF2E7D32;
    private static final int C_OK = 0xFFF9A825;
    private static final int C_BAD = 0xFFC62828;
    private static final int V_GOOD = 0xFF66BB6A;
    private static final int V_NEAR = 0xFFFFCA28;
    private static final int V_BAD = 0xFFFF6E6E;
    private static final int V_NONE = Color.WHITE;
    private static final int BG_CARD = 0xF0141618;
    private static final int BG_TILE = 0xFF23272B;
    private static final int TXT_DIM = 0xFFA9B0B8;

    private final AccessibilityService svc;
    private final WindowManager wm;
    private final Locale br = Locale.forLanguageTag("pt-BR");

    private final LinearLayout root;
    private final GradientDrawable headerBg;
    private final TextView verdict;
    private final TextView badge;
    private final Tile tKm, tHour, tProfit, tTraffic;
    private final TextView footer;
    private final TextView routeBox;
    private final GradientDrawable routeBg;
    private final LinearLayout list;
    private boolean attached;
    private int lastYdp = 90;
    private boolean netMode;
    private String dayInfo = "";

    /** "HOJE R$ 45/100": progresso da meta, mostrado no canto da faixa do veredito. */
    void setDayInfo(String s) { this.dayInfo = s == null ? "" : s; }

    /** Meta em lucro líquido (true) ou faturamento bruto (false): define o quadro de R$/hora. */
    void setNetMode(boolean net) { this.netMode = net; }

    /** Um quadro: rótulo pequeno, valor grande colorido, complemento opcional. */
    private final class Tile {
        final LinearLayout box;
        final TextView label, value, sub;

        Tile(String title) {
            box = new LinearLayout(svc);
            box.setOrientation(LinearLayout.VERTICAL);
            box.setGravity(Gravity.CENTER_HORIZONTAL);
            box.setPadding(dp(4), dp(6), dp(4), dp(6));
            GradientDrawable g = new GradientDrawable();
            g.setColor(BG_TILE);
            g.setCornerRadius(dp(10));
            box.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            box.setLayoutParams(lp);

            label = text(10, true, TXT_DIM);
            label.setText(title);
            label.setLetterSpacing(0.06f);
            value = text(21, true, V_NONE);
            sub = text(11, false, TXT_DIM);
            box.addView(label);
            box.addView(value);
            box.addView(sub);
        }

        void set(String v, String s, Evaluation.Mark m) {
            value.setText(v);
            value.setTextColor(color(m));
            sub.setText(s == null ? "" : s);
            sub.setVisibility(s == null || s.isEmpty() ? View.GONE : View.VISIBLE);
        }
    }

    OverlayCard(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);

        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable cardBg = new GradientDrawable();
        cardBg.setColor(BG_CARD);
        cardBg.setCornerRadius(dp(16));
        root.setBackground(cardBg);
        root.setPadding(0, 0, 0, dp(8));

        // Faixa do veredito
        LinearLayout header = new LinearLayout(svc);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(7), dp(14), dp(7));
        headerBg = new GradientDrawable();
        float r = dp(16);
        headerBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        header.setBackground(headerBg);
        verdict = text(20, true, Color.WHITE);
        verdict.setGravity(Gravity.START);
        verdict.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        badge = text(12, true, Color.WHITE);
        badge.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(verdict);
        header.addView(badge);
        root.addView(header);

        // Quadros
        LinearLayout tiles = new LinearLayout(svc);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(dp(5), dp(8), dp(5), 0);
        tKm = new Tile("R$/KM");
        tHour = new Tile("LUCRO/H");
        tProfit = new Tile("LUCRO");
        tTraffic = new Tile("TRÂNSITO");
        tiles.addView(tKm.box);
        tiles.addView(tHour.box);
        tiles.addView(tProfit.box);
        tiles.addView(tTraffic.box);
        root.addView(tiles);

        // Caixa da rota: no trajeto (verde) / fora da rota (vermelho) / destino (neutro)
        routeBox = text(14, true, Color.WHITE);
        routeBox.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        routeBox.setSingleLine(true);
        routeBox.setEllipsize(TextUtils.TruncateAt.END);
        routeBox.setPadding(dp(10), dp(7), dp(10), dp(7));
        routeBg = new GradientDrawable();
        routeBg.setCornerRadius(dp(10));
        routeBox.setBackground(routeBg);
        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        rlp.setMargins(dp(8), dp(8), dp(8), 0);
        routeBox.setLayoutParams(rlp);
        root.addView(routeBox);

        // Rodapé
        footer = text(12, false, TXT_DIM);
        footer.setPadding(dp(12), dp(7), dp(12), 0);
        footer.setSingleLine(true);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(footer);

        // Lista de ofertas simultâneas
        list = new LinearLayout(svc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), 0);
        root.addView(list);
    }

    /** Uma oferta. */
    void show(Evaluation e, int yDp) {
        fill(e, e.verdictLabelShort());
        list.removeAllViews();
        list.setVisibility(View.GONE);
        attach(yDp);
    }

    /**
     * Ofertas simultâneas (já ordenadas, melhor primeiro): quadros da melhor e uma linha por
     * oferta. O valor em R$ de cada linha é o que você procura na tela do app de corrida.
     */
    void showMany(List<Evaluation> ranked, int yDp) {
        Evaluation best = ranked.get(0);
        fill(best, String.format(br, "MELHOR: R$ %.2f · %s", best.offer.fare, best.verdictLabelShort()));

        list.removeAllViews();
        int max = Math.min(ranked.size(), 4);
        for (int i = 0; i < max; i++) {
            Evaluation e = ranked.get(i);
            TextView row = text(13, i == 0, i == 0 ? Color.WHITE : TXT_DIM);
            row.setGravity(Gravity.START);
            row.setSingleLine(true);
            row.setEllipsize(TextUtils.TruncateAt.END);
            row.setPadding(0, dp(3), 0, 0);
            row.setText((i + 1) + "º  " + e.compactLine());
            list.addView(row);
        }
        list.setVisibility(View.VISIBLE);
        attach(yDp);
    }

    private void fill(Evaluation e, String title) {
        int c;
        switch (e.verdict) {
            case GOOD: c = C_GOOD; break;
            case OK: c = C_OK; break;
            default: c = C_BAD; break;
        }
        headerBg.setColor(c);
        int fg = e.verdict == Evaluation.Verdict.OK ? 0xFF1B1B1B : Color.WHITE;
        verdict.setTextColor(fg);
        badge.setTextColor(fg);
        verdict.setText(title);
        badge.setText(dayInfo);
        badge.setVisibility(dayInfo.isEmpty() ? View.GONE : View.VISIBLE);

        tKm.set(String.format(br, "%.2f", e.rsPerKm), null, e.markKm);
        if (netMode) {
            tHour.label.setText("LUCRO/H");
            tHour.set(String.format(br, "%.0f", e.profitPerHour),
                    String.format(br, "bruto %.0f", e.rsPerHour), e.markProfitHour);
        } else {
            tHour.label.setText("R$/HORA");
            tHour.set(String.format(br, "%.0f", e.rsPerHour),
                    String.format(br, "lucro %.0f", e.profitPerHour), e.markHour);
        }
        tProfit.set(String.format(br, "%.0f%%", e.profitPct),
                String.format(br, "R$ %.2f", e.profit), e.markProfit);
        if (e.tripSpeedKmh > 0) {
            tTraffic.set(String.format(br, "%.0f", e.tripSpeedKmh), e.trafficLabel, e.markTraffic);
            android.text.SpannableString sp = new android.text.SpannableString(
                    String.format(br, "%.0f km/h", e.tripSpeedKmh));
            int start = sp.length() - 5;
            sp.setSpan(new android.text.style.RelativeSizeSpan(0.5f), start, sp.length(), 0);
            tTraffic.value.setText(sp);
        } else {
            tTraffic.set("–", null, Evaluation.Mark.NONE);
        }

        String dest = place(e.offer.destination);
        if (e.routeConfigured && e.routeHit != null) {
            routeBg.setColor(0xFF1E4620);
            routeBox.setTextColor(V_GOOD);
            routeBox.setText("✔ NO TRAJETO · " + (dest.isEmpty() ? e.routeHit : dest));
        } else if (e.routeConfigured && !dest.isEmpty()) {
            routeBg.setColor(0xFF4A1F1F);
            routeBox.setTextColor(V_BAD);
            routeBox.setText("✘ FORA DA ROTA · " + dest);
        } else if (e.routeConfigured) {
            routeBg.setColor(0xFF4A3B12);
            routeBox.setTextColor(V_NEAR);
            routeBox.setText("? DESTINO NÃO LIDO");
        } else {
            routeBg.setColor(BG_TILE);
            routeBox.setTextColor(Color.WHITE);
            routeBox.setText("DESTINO · " + (dest.isEmpty() ? "não lido" : dest));
        }

        // "km em X min" (e não "km · X min") para a leitura por imagem nunca confundir o
        // rodapé da etiqueta com uma oferta.
        StringBuilder f = new StringBuilder(String.format(br, "R$ %.2f · %.1f km em %.0f min",
                e.offer.fare, e.totalKm, e.totalMin));
        if (e.offer.pickupKm > 0) {
            f.append(String.format(br, " · busca %.1f km em %.0f min", e.offer.pickupKm, e.offer.pickupMin));
        }
        footer.setText(f.toString());
    }

    /**
     * Parte útil do destino para decidir rápido: bairro e cidade.
     * "Travessa Natividade Batista, 45, | Santa Catarina, São Gonçalo" → "Santa Catarina, São Gonçalo".
     */
    static String place(String dest) {
        if (dest == null) return "";
        String d = dest.trim();
        int bar = d.lastIndexOf(" | ");
        if (bar >= 0) return d.substring(bar + 3).trim();
        String[] parts = d.split(",");
        java.util.List<String> keep = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !t.matches("\\d+.*")) keep.add(t);
        }
        if (keep.size() >= 2) return keep.get(keep.size() - 2) + ", " + keep.get(keep.size() - 1);
        return d;
    }

    private static int color(Evaluation.Mark m) {
        switch (m) {
            case GOOD: return V_GOOD;
            case NEAR: return V_NEAR;
            case BAD: return V_BAD;
            default: return V_NONE;
        }
    }

    private void attach(int yDp) {
        lastYdp = yDp;
        WindowManager.LayoutParams lp = params(yDp);
        try {
            if (attached) {
                wm.updateViewLayout(root, lp);
            } else {
                wm.addView(root, lp);
                attached = true;
            }
        } catch (RuntimeException ignored) {
            attached = false;
        }
    }

    // ---- Aviso curto (lembrete / confirmação), separado da etiqueta de oferta ----
    private TextView hint;
    private boolean hintAttached;
    private final android.os.Handler hintHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable hideHint = this::removeHint;

    void showHint(String text, long ms) {
        if (hint == null) {
            hint = text(15, true, Color.WHITE);
            hint.setGravity(Gravity.START);
            hint.setPadding(dp(14), dp(10), dp(14), dp(10));
            GradientDrawable g = new GradientDrawable();
            g.setColor(0xF01565C0);
            g.setCornerRadius(dp(14));
            hint.setBackground(g);
        }
        hint.setText(text);
        WindowManager.LayoutParams lp = params(lastYdp);
        try {
            if (hintAttached) wm.updateViewLayout(hint, lp);
            else { wm.addView(hint, lp); hintAttached = true; }
        } catch (RuntimeException ignored) {
            hintAttached = false;
        }
        hintHandler.removeCallbacks(hideHint);
        hintHandler.postDelayed(hideHint, ms);
    }

    private void removeHint() {
        if (!hintAttached) return;
        try { wm.removeView(hint); } catch (RuntimeException ignored) { /* já removido */ }
        hintAttached = false;
    }

    void hide() {
        if (!attached) return;
        try {
            wm.removeView(root);
        } catch (RuntimeException ignored) {
            // já removida
        }
        attached = false;
    }

    boolean isShowing() { return attached; }

    /** Área da etiqueta na tela (para a leitura por imagem não ler a própria etiqueta). */
    Rect screenRect() {
        if (!attached) return null;
        if (root.getWidth() == 0 || root.getHeight() == 0) {
            // Recém-desenhada: usa a área estimada (largura toda, ~330 dp de altura).
            int w = svc.getResources().getDisplayMetrics().widthPixels;
            int top = dp(lastYdp);
            return new Rect(0, Math.max(0, top - dp(8)), w, top + dp(330));
        }
        int[] xy = new int[2];
        root.getLocationOnScreen(xy);
        int m = dp(8);
        return new Rect(xy[0] - m, xy[1] - m, xy[0] + root.getWidth() + m, xy[1] + root.getHeight() + m);
    }

    private WindowManager.LayoutParams params(int yDp) {
        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.WRAP_CONTENT,
                WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL;
        lp.y = dp(yDp);
        lp.horizontalMargin = 0.03f;
        return lp;
    }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(svc);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setIncludeFontPadding(false);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                svc.getResources().getDisplayMetrics()));
    }
}
