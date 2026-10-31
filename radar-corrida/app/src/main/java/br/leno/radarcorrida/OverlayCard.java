package br.leno.radarcorrida;

import android.accessibilityservice.AccessibilityService;
import android.graphics.Color;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
 * Etiqueta desenhada por cima do app de corrida.
 *
 * Usa TYPE_ACCESSIBILITY_OVERLAY (dispensa a permissão "sobrepor a outros apps") e é
 * NOT_TOUCHABLE: toques passam direto para o app de baixo. A etiqueta nunca cobre nem
 * aciona o botão de aceitar — a decisão é sempre do motorista.
 */
final class OverlayCard {
    private final AccessibilityService svc;
    private final WindowManager wm;
    private final LinearLayout root;
    private final TextView label;
    private final TextView headline;
    private final TextView details;
    private final TextView reasons;
    private final LinearLayout list;
    private final GradientDrawable bg;
    private boolean attached;

    OverlayCard(AccessibilityService svc) {
        this.svc = svc;
        this.wm = (WindowManager) svc.getSystemService(AccessibilityService.WINDOW_SERVICE);

        root = new LinearLayout(svc);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = dp(12);
        root.setPadding(pad, dp(8), pad, dp(10));
        bg = new GradientDrawable();
        bg.setCornerRadius(dp(14));
        root.setBackground(bg);
        root.setAlpha(0.94f);

        label = text(16, true);
        headline = text(22, true);
        details = text(13, false);
        reasons = text(12, false);
        root.addView(label);
        root.addView(headline);
        root.addView(details);
        root.addView(reasons);
        list = new LinearLayout(svc);
        list.setOrientation(LinearLayout.VERTICAL);
        list.setPadding(0, dp(6), 0, 0);
        root.addView(list);
    }

    /** Uma oferta: veredito, métricas e motivos. */
    void show(Evaluation e, int yDp) {
        paint(e.verdict);
        list.removeAllViews();
        list.setVisibility(View.GONE);

        label.setText(e.verdictLabel());
        headline.setText(e.headline());
        details.setText(e.details());
        reasons.setText(e.reasonsLine());
        reasons.setVisibility(e.reasons.isEmpty() ? View.GONE : View.VISIBLE);
        attach(yDp);
    }

    /**
     * Ofertas simultâneas (lista já ordenada, melhor primeiro): destaque para a melhor e uma
     * linha curta por oferta. O valor em R$ de cada linha é o que você procura na tela do app.
     */
    void showMany(List<Evaluation> ranked, int yDp) {
        Evaluation best = ranked.get(0);
        int fg = paint(best.verdict);

        label.setText(String.format(Locale.ROOT, "%d OFERTAS · MELHOR: R$ %s",
                ranked.size(), money(best.offer.fare)));
        headline.setText(best.headline());
        details.setText(best.verdictLabel() + " · " + best.details());
        reasons.setVisibility(View.GONE);

        list.removeAllViews();
        int max = Math.min(ranked.size(), 4);
        for (int i = 0; i < max; i++) {
            TextView row = text(13, i == 0);
            row.setGravity(Gravity.START);
            row.setTextColor(fg);
            row.setAlpha(i == 0 ? 1f : 0.85f);
            row.setText((i + 1) + "º  " + ranked.get(i).compactLine());
            list.addView(row);
        }
        list.setVisibility(View.VISIBLE);
        attach(yDp);
    }

    private int paint(Evaluation.Verdict v) {
        int color;
        switch (v) {
            case GOOD: color = 0xFF2E7D32; break;
            case OK: color = 0xFFF9A825; break;
            default: color = 0xFFC62828; break;
        }
        bg.setColor(color);
        int fg = v == Evaluation.Verdict.OK ? 0xFF1B1B1B : Color.WHITE;
        label.setTextColor(fg);
        headline.setTextColor(fg);
        details.setTextColor(fg);
        reasons.setTextColor(fg);
        return fg;
    }

    private static String money(double v) {
        return String.format(Locale.forLanguageTag("pt-BR"), "%.2f", v);
    }

    private void attach(int yDp) {
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
        lp.horizontalMargin = 0.04f;
        return lp;
    }

    private TextView text(int sp, boolean bold) {
        TextView t = new TextView(svc);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        if (bold) t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setGravity(Gravity.CENTER_HORIZONTAL);
        t.setLayoutParams(new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT));
        return t;
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                svc.getResources().getDisplayMetrics()));
    }

    @SuppressWarnings("unused")
    View view() { return root; }
}
