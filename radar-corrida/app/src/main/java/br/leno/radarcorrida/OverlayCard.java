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
 *   │ R$/KM    │GANHO/HORA│ LUCRO    │ TRÂNSITO│  ← 4 quadros, cada valor na cor
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
    /** Tamanho escolhido pelo motorista (1,0 = 100%): multiplica letras, quadros e largura. */
    final float scale;
    private final WindowManager wm;
    private final Locale br = Locale.forLanguageTag("pt-BR");

    private final LinearLayout root;
    private final GradientDrawable cardBg;
    private final GradientDrawable headerBg;
    private final TextView verdict;
    private final TextView badge;
    private final Tile tKm, tHour, tProfit, tTraffic;
    private final TextView footer;
    private final TextView routeBox;
    private final GradientDrawable routeBg;
    private final LinearLayout list;
    private final java.util.List<View> fullViews = new java.util.ArrayList<>();
    // Modo compacto: faixa baixa no topo, deixa o mapa (e a rota até o passageiro) livre.
    private final LinearLayout compact;
    private final GradientDrawable cPillBg;
    private final TextView cVerdict, cDay, cHour, cKm, cProfit, cLine;
    // Coluna estreita: fica no espaço vazio à direita do cartão da Uber; o mapa fica 100% livre.
    private final LinearLayout column;
    private final GradientDrawable kPillBg;
    private final TextView kVerdict, kRoute, kHour, kKm, kProfit, kPick;
    /** 0 = cartão completo, 1 = faixa compacta, 2 = coluna à direita. */
    static final int MODE_FULL = 0, MODE_STRIP = 1, MODE_COLUMN = 2;
    private int mode = MODE_STRIP;
    private boolean attached;
    private int lastYdp = 90;
    private boolean netMode;
    private String dayInfo = "";

    /** "HOJE R$ 45/100": progresso da meta, mostrado no canto da faixa do veredito. */
    void setDayInfo(String s) { this.dayInfo = s == null ? "" : s; }

    /** Etiqueta compacta (padrão) ou detalhada (4 quadros + destino + rodapé). */
    void setMode(int m) {
        if (m == mode) return;
        mode = m;
        applyMode();
        if (attached) attach(lastYdp);
    }

    private void applyMode() {
        for (View v : fullViews) v.setVisibility(mode == MODE_FULL ? View.VISIBLE : View.GONE);
        compact.setVisibility(mode == MODE_STRIP ? View.VISIBLE : View.GONE);
        column.setVisibility(mode == MODE_COLUMN ? View.VISIBLE : View.GONE);
        if (mode != MODE_FULL) list.setVisibility(View.GONE);
        root.setPadding(0, 0, 0, mode == MODE_FULL ? dp(8) : 0);
    }

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
            box.setPadding(dp(3), dp(7), dp(3), dp(7));
            GradientDrawable g = new GradientDrawable();
            g.setColor(BG_TILE);
            g.setCornerRadius(dp(10));
            box.setBackground(g);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.MATCH_PARENT, 1f);
            lp.setMargins(dp(3), 0, dp(3), 0);
            box.setLayoutParams(lp);

            label = text(12, true, TXT_DIM);
            label.setText(title);
            label.setLetterSpacing(0.06f);
            value = text(31, true, V_NONE);
            value.setMaxLines(1);
            // Números grandes, mas sem cortar: em tela estreita o Android reduz até caber.
            if (android.os.Build.VERSION.SDK_INT >= 26) {
                value.setLayoutParams(new LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT, dp(42)));
                value.setGravity(Gravity.CENTER);
                autoSize(value, 18, 34);
            }
            sub = text(13, false, TXT_DIM);
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

    OverlayCard(AccessibilityService svc, float scale) {
        this.svc = svc;
        this.scale = Math.max(0.5f, Math.min(2f, scale));
        this.wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);

        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        cardBg = new GradientDrawable();
        cardBg.setColor(BG_CARD);
        cardBg.setCornerRadius(dp(16));
        root.setBackground(cardBg);
        root.setPadding(0, 0, 0, dp(8));

        // Faixa do veredito
        LinearLayout header = new LinearLayout(svc);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(dp(14), dp(8), dp(14), dp(8));
        headerBg = new GradientDrawable();
        float r = dp(16);
        headerBg.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        header.setBackground(headerBg);
        verdict = text(27, true, Color.WHITE);
        verdict.setGravity(Gravity.START);
        verdict.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        badge = text(15, true, Color.WHITE);
        badge.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        header.addView(verdict);
        header.addView(badge);
        root.addView(header);
        fullViews.add(header);

        // Quadros
        LinearLayout tiles = new LinearLayout(svc);
        tiles.setOrientation(LinearLayout.HORIZONTAL);
        tiles.setPadding(dp(5), dp(8), dp(5), 0);
        tKm = new Tile("R$/KM");
        tHour = new Tile("GANHO/HORA");
        tProfit = new Tile("LUCRO");
        tTraffic = new Tile("TRÂNSITO");
        tiles.addView(tKm.box);
        tiles.addView(tHour.box);
        tiles.addView(tProfit.box);
        tiles.addView(tTraffic.box);
        root.addView(tiles);
        fullViews.add(tiles);

        // Caixa da rota: no trajeto (verde) / fora da rota (vermelho) / destino (neutro)
        routeBox = text(17, true, Color.WHITE);
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
        fullViews.add(routeBox);

        // Rodapé
        footer = text(14, false, TXT_DIM);
        footer.setPadding(dp(12), dp(7), dp(12), 0);
        footer.setSingleLine(true);
        footer.setEllipsize(TextUtils.TruncateAt.END);
        root.addView(footer);
        fullViews.add(footer);

        // Lista de ofertas simultâneas
        list = new LinearLayout(svc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(dp(12), dp(4), dp(12), 0);
        root.addView(list);

        // ---- Compacta: [ACEITAR] | R$/h | R$/km | lucro ; embaixo uma linha com destino e busca
        compact = new LinearLayout(svc);
        compact.setOrientation(LinearLayout.VERTICAL);
        compact.setPadding(dp(6), dp(6), dp(6), dp(6));
        LinearLayout row = new LinearLayout(svc);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout pill = new LinearLayout(svc);
        pill.setOrientation(LinearLayout.VERTICAL);
        pill.setGravity(Gravity.CENTER);
        pill.setPadding(dp(8), dp(6), dp(8), dp(6));
        cPillBg = new GradientDrawable();
        cPillBg.setCornerRadius(dp(10));
        pill.setBackground(cPillBg);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.MATCH_PARENT, 1.15f);
        plp.setMargins(0, 0, dp(4), 0);
        pill.setLayoutParams(plp);
        cVerdict = text(19, true, Color.WHITE);
        cVerdict.setMaxLines(1);
        cDay = text(11, true, Color.WHITE);
        pill.addView(cVerdict);
        pill.addView(cDay);
        row.addView(pill);
        cHour = compactValue(row, "R$/HORA");
        cKm = compactValue(row, "R$/KM");
        cProfit = compactValue(row, "LUCRO R$");
        compact.addView(row);
        cLine = text(13, true, Color.WHITE);
        cLine.setGravity(Gravity.START);
        cLine.setSingleLine(true);
        cLine.setEllipsize(TextUtils.TruncateAt.END);
        cLine.setPadding(dp(4), dp(5), dp(4), 0);
        compact.addView(cLine);
        root.addView(compact, 0);

        // ---- Coluna: [ACEITAR] ✔/✘ · R$/h · R$/km · lucro · busca, um embaixo do outro
        column = new LinearLayout(svc);
        column.setOrientation(LinearLayout.VERTICAL);
        column.setGravity(Gravity.CENTER_HORIZONTAL);
        column.setPadding(dp(4), dp(5), dp(4), dp(6));
        LinearLayout kPill = new LinearLayout(svc);
        kPill.setOrientation(LinearLayout.VERTICAL);
        kPill.setGravity(Gravity.CENTER);
        kPill.setPadding(dp(2), dp(6), dp(2), dp(6));
        kPillBg = new GradientDrawable();
        kPillBg.setCornerRadius(dp(10));
        kPill.setBackground(kPillBg);
        kVerdict = text(15, true, Color.WHITE);
        kVerdict.setMaxLines(1);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            kVerdict.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(20)));
            kVerdict.setGravity(Gravity.CENTER);
            autoSize(kVerdict, 9, 15);
        }
        kPill.addView(kVerdict);
        column.addView(kPill);
        kRoute = text(12, true, Color.WHITE);
        kRoute.setPadding(0, dp(3), 0, 0);
        column.addView(kRoute);
        kHour = columnValue("R$/H");
        kKm = columnValue("R$/KM");
        kProfit = columnValue("LUCRO");
        kPick = columnValue("BUSCA");
        root.addView(column, 0);
        applyMode();
    }

    private TextView columnValue(String title) {
        TextView l = text(9, true, TXT_DIM);
        l.setText(title);
        l.setPadding(0, dp(6), 0, 0);
        TextView v = text(22, true, V_NONE);
        v.setMaxLines(1);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(28)));
            v.setGravity(Gravity.CENTER);
            autoSize(v, 11, 24);
        }
        column.addView(l);
        column.addView(v);
        return v;
    }

    /** Coluna da faixa compacta: rótulo pequeno em cima, número grande embaixo. */
    private TextView compactValue(LinearLayout row, String title) {
        LinearLayout box = new LinearLayout(svc);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.CENTER_HORIZONTAL);
        box.setLayoutParams(new LinearLayout.LayoutParams(0,
                LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        TextView l = text(10, true, TXT_DIM);
        l.setText(title);
        TextView v = text(27, true, V_NONE);
        v.setMaxLines(1);
        if (android.os.Build.VERSION.SDK_INT >= 26) {
            v.setLayoutParams(new LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT, dp(34)));
            v.setGravity(Gravity.CENTER);
            autoSize(v, 14, 28);
        }
        box.addView(l);
        box.addView(v);
        row.addView(box);
        return v;
    }

    /** Uma oferta. */
    void show(Evaluation e, int yDp) {
        fill(e, e.verdictLabelShort());
        list.removeAllViews();
        list.setVisibility(View.GONE);
        attach(yDp);
        pulseIfGood(e);
    }

    private android.animation.ValueAnimator pulse;

    /**
     * Os números ficam SEMPRE parados na tela. Só a faixa do veredito e a borda do cartão
     * piscam (verde forte ↔ verde claro, vermelho ↔ vermelho claro) por uns 4 segundos,
     * para puxar o olho; depois ficam na cor fixa. AVALIAR (amarelo) não pisca.
     */
    private void pulseIfGood(Evaluation e) {
        stopPulse();
        final int base, bright;
        if (e.verdict == Evaluation.Verdict.GOOD) { base = C_GOOD; bright = 0xFF00E676; }
        else if (e.verdict == Evaluation.Verdict.BAD) { base = C_BAD; bright = 0xFFFF1744; }
        else {
            cardBg.setStroke(dp(3), C_OK);
            return;
        }
        cardBg.setStroke(dp(4), base);
        pulse = android.animation.ValueAnimator.ofArgb(base, bright);
        pulse.setDuration(350);
        pulse.setRepeatMode(android.animation.ValueAnimator.REVERSE);
        pulse.setRepeatCount(11);
        pulse.addUpdateListener(a -> {
            int c = (int) a.getAnimatedValue();
            headerBg.setColor(c);
            cPillBg.setColor(c);
            kPillBg.setColor(c);
            cardBg.setStroke(dp(4), c);
        });
        pulse.addListener(new android.animation.AnimatorListenerAdapter() {
            @Override
            public void onAnimationEnd(android.animation.Animator a) {
                headerBg.setColor(base);
                cPillBg.setColor(base);
                kPillBg.setColor(base);
                cardBg.setStroke(dp(4), base);
            }
        });
        pulse.start();
    }

    private void stopPulse() {
        if (pulse != null) {
            // Sem listeners antes de cancelar: senão o fim da animação antiga pintaria a cor
            // do veredito anterior por cima do novo.
            pulse.removeAllListeners();
            pulse.removeAllUpdateListeners();
            pulse.cancel();
        }
        pulse = null;
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
        list.setVisibility(mode == MODE_FULL ? View.VISIBLE : View.GONE);
        attach(yDp);
        pulseIfGood(best);
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
        // Ganho por hora no ritmo desta corrida (busca + viagem): "R$ 68/h", e o outro valor embaixo.
        if (netMode) {
            tHour.label.setText("LUCRO/HORA");
            tHour.set("", String.format(br, "bruto R$%.0f/h", e.rsPerHour), e.markProfitHour);
            tHour.value.setText(perHour(e.profitPerHour));
        } else {
            tHour.label.setText("GANHO/HORA");
            tHour.set("", String.format(br, "lucro R$%.0f/h", e.profitPerHour), e.markHour);
            tHour.value.setText(perHour(e.rsPerHour));
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
            if (e.offer.pickupEstimated) f.append(" (estimada)");
        }
        footer.setText(f.toString());

        // ---- Faixa compacta
        cPillBg.setColor(c);
        cVerdict.setTextColor(fg);
        cDay.setTextColor(fg);
        cVerdict.setText(title.replace("MELHOR: ", ""));
        cDay.setText(dayInfo);
        cDay.setVisibility(dayInfo.isEmpty() ? View.GONE : View.VISIBLE);
        double ph = netMode ? e.profitPerHour : e.rsPerHour;
        cHour.setText(String.format(br, "%.0f", ph));
        cHour.setTextColor(color(netMode ? e.markProfitHour : e.markHour));
        cKm.setText(String.format(br, "%.2f", e.rsPerKm));
        cKm.setTextColor(color(e.markKm));
        cProfit.setText(String.format(br, "%.0f", e.profit));
        cProfit.setTextColor(color(e.markProfit));
        StringBuilder cl = new StringBuilder();
        if (e.routeConfigured && e.routeHit != null) cl.append("✔ ");
        else if (e.routeConfigured) cl.append("✘ ");
        cl.append(dest.isEmpty() ? "destino não lido" : dest);
        if (e.offer.pickupKm > 0) cl.append(String.format(br, " · busca %.1f km %.0f min",
                e.offer.pickupKm, e.offer.pickupMin));
        cLine.setText(cl.toString());
        cLine.setTextColor(e.routeConfigured && e.routeHit != null ? V_GOOD
                : e.routeConfigured ? V_BAD : Color.WHITE);

        // ---- Coluna
        kPillBg.setColor(c);
        kVerdict.setTextColor(fg);
        kVerdict.setText(e.verdictLabelShort());
        if (e.routeConfigured && e.routeHit != null) { kRoute.setText("✔ rota"); kRoute.setTextColor(V_GOOD); }
        else if (e.routeConfigured) { kRoute.setText("✘ rota"); kRoute.setTextColor(V_BAD); }
        else kRoute.setText("");
        kRoute.setVisibility(e.routeConfigured ? View.VISIBLE : View.GONE);
        kHour.setText(String.format(br, "%.0f", ph));
        kHour.setTextColor(color(netMode ? e.markProfitHour : e.markHour));
        kKm.setText(String.format(br, "%.2f", e.rsPerKm));
        kKm.setTextColor(color(e.markKm));
        kProfit.setText(String.format(br, "%.0f", e.profit));
        kProfit.setTextColor(color(e.markProfit));
        kPick.setText(e.offer.pickupKm > 0 ? String.format(br, "%.1f", e.offer.pickupKm) : "–");
        kPick.setTextColor(Color.WHITE);
    }

    /**
     * Parte útil do destino para decidir rápido: bairro e cidade.
     * "Travessa Natividade Batista, 45, | Santa Catarina, São Gonçalo" → "Santa Catarina, São Gonçalo".
     */
    /** "R$ 68/h" com "R$" e "/h" menores, para o número continuar legível de relance. */
    private CharSequence perHour(double v) {
        String n = String.format(br, "%.0f", v);
        android.text.SpannableString sp = new android.text.SpannableString("R$ " + n + "/h");
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.5f), 0, 3, 0);
        sp.setSpan(new android.text.style.RelativeSizeSpan(0.5f), 3 + n.length(), sp.length(), 0);
        return sp;
    }

    static String place(String dest) {
        if (dest == null) return "";
        // O destino vem em linhas ("Rua X, 211, Santa | Izabel, São Gonçalo"). Linhas que são
        // rótulo do mapa lidos por imagem ("AIE", "MARAVISTA", "L08") ficam de fora.
        StringBuilder sb = new StringBuilder();
        for (String seg : dest.split(" \\| ")) {
            String t = seg.trim();
            if (t.length() < 4 || !t.matches(".*[a-zà-ÿ].*")) continue;
            if (sb.length() > 0) sb.append(' ');
            sb.append(t);
        }
        String d = sb.toString().trim();
        if (d.isEmpty()) return "";
        String[] parts = d.split(",");
        java.util.List<String> keep = new java.util.ArrayList<>();
        for (String p : parts) {
            String t = p.trim();
            if (!t.isEmpty() && !t.matches("\\d+.*")) keep.add(t);
        }
        if (keep.size() >= 2) {
            String a = keep.get(keep.size() - 2), b = keep.get(keep.size() - 1);
            return a.equalsIgnoreCase(b) ? b : a + ", " + b;   // "Itaboraí, Itaboraí" → "Itaboraí"
        }
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
        stopPulse();
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
            int top = rawDp(lastYdp);
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
        lp.y = rawDp(yDp);
        lp.horizontalMargin = 0.03f;
        if (mode == MODE_COLUMN) {
            lp.width = dp(66);
            lp.gravity = Gravity.TOP | Gravity.END;
            lp.horizontalMargin = 0f;
            lp.x = rawDp(4);
        }
        return lp;
    }

    private TextView text(int sp, boolean bold, int color) {
        TextView t = new TextView(svc);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp * scale);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setTextColor(color);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setIncludeFontPadding(false);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    /** Medida que acompanha o tamanho escolhido. */
    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v * scale,
                svc.getResources().getDisplayMetrics()));
    }

    /** Medida fixa (posição na tela): a altura não muda quando o tamanho muda. */
    private int rawDp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                svc.getResources().getDisplayMetrics()));
    }

    /** Letra que encolhe até caber, na escala escolhida (só Android 8+). */
    private void autoSize(TextView t, int minSp, int maxSp) {
        if (android.os.Build.VERSION.SDK_INT < 26) return;
        int lo = Math.max(6, Math.round(minSp * scale));
        int hi = Math.max(lo + 1, Math.round(maxSp * scale));
        t.setAutoSizeTextTypeUniformWithConfiguration(lo, hi, 1, TypedValue.COMPLEX_UNIT_SP);
    }
}
