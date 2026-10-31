package br.leno.radarcorrida;

import android.accessibilityservice.AccessibilityService;
import android.annotation.TargetApi;
import android.graphics.Bitmap;
import android.graphics.Rect;
import android.hardware.HardwareBuffer;
import android.os.Build;
import android.view.Display;

import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.text.Text;
import com.google.mlkit.vision.text.TextRecognition;
import com.google.mlkit.vision.text.TextRecognizer;
import com.google.mlkit.vision.text.latin.TextRecognizerOptions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Leitura por imagem: captura a tela pelo serviço de acessibilidade (Android 11+) e extrai
 * as linhas de texto com o ML Kit, no próprio aparelho. A imagem fica só na memória e é
 * descartada logo após a leitura; nada é gravado nem enviado.
 *
 * Usado quando o app de corrida não expõe o texto da oferta para a acessibilidade
 * (caso da Uber em 31/10/2026).
 */
final class ScreenOcr {
    interface Callback {
        void onLines(List<String> lines);
    }

    private final AccessibilityService svc;
    private TextRecognizer recognizer;
    private boolean busy;

    ScreenOcr(AccessibilityService svc) {
        this.svc = svc;
    }

    static boolean supported() {
        return Build.VERSION.SDK_INT >= 30;
    }

    boolean isBusy() { return busy; }

    /**
     * Captura e lê a tela. Linhas cujo centro cai dentro de {@code exclude} (a própria
     * etiqueta do Radar) são descartadas. O callback roda na thread principal; recebe
     * lista vazia em caso de falha.
     */
    @TargetApi(30)
    void read(final Rect exclude, final Callback cb) {
        if (!supported() || busy) return;
        busy = true;
        try {
            svc.takeScreenshot(Display.DEFAULT_DISPLAY, svc.getMainExecutor(),
                    new AccessibilityService.TakeScreenshotCallback() {
                        @Override
                        public void onSuccess(AccessibilityService.ScreenshotResult result) {
                            process(result, exclude, cb);
                        }

                        @Override
                        public void onFailure(int errorCode) {
                            busy = false;
                            cb.onLines(Collections.<String>emptyList());
                        }
                    });
        } catch (RuntimeException e) {
            busy = false;
            cb.onLines(Collections.<String>emptyList());
        }
    }

    @TargetApi(30)
    private void process(AccessibilityService.ScreenshotResult result, final Rect exclude,
                         final Callback cb) {
        Bitmap soft = null;
        HardwareBuffer hb = result.getHardwareBuffer();
        try {
            Bitmap hw = Bitmap.wrapHardwareBuffer(hb, result.getColorSpace());
            if (hw != null) {
                soft = hw.copy(Bitmap.Config.ARGB_8888, false);
                hw.recycle();
            }
        } catch (RuntimeException ignored) {
            soft = null;
        } finally {
            hb.close();
        }
        if (soft == null) {
            busy = false;
            cb.onLines(Collections.<String>emptyList());
            return;
        }

        if (recognizer == null) {
            recognizer = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS);
        }
        final Bitmap bmp = soft;
        recognizer.process(InputImage.fromBitmap(bmp, 0))
                .addOnSuccessListener(text -> cb.onLines(toLines(text, exclude)))
                .addOnFailureListener(e -> cb.onLines(Collections.<String>emptyList()))
                .addOnCompleteListener(t -> {
                    bmp.recycle();
                    busy = false;
                });
    }

    /** Linhas em ordem de leitura (de cima para baixo, da esquerda para a direita). */
    private static List<String> toLines(Text text, Rect exclude) {
        List<Text.Line> all = new ArrayList<>();
        for (Text.TextBlock b : text.getTextBlocks()) all.addAll(b.getLines());
        Collections.sort(all, (a, b) -> {
            Rect ra = a.getBoundingBox();
            Rect rb = b.getBoundingBox();
            int ta = ra != null ? ra.top : 0;
            int tb = rb != null ? rb.top : 0;
            // Mesma linha visual se a diferença de topo for pequena.
            if (Math.abs(ta - tb) > 12) return Integer.compare(ta, tb);
            int la = ra != null ? ra.left : 0;
            int lb = rb != null ? rb.left : 0;
            return Integer.compare(la, lb);
        });
        List<String> out = new ArrayList<>();
        for (Text.Line l : all) {
            Rect r = l.getBoundingBox();
            if (exclude != null && r != null && exclude.contains(r.centerX(), r.centerY())) continue;
            String s = l.getText().trim();
            if (!s.isEmpty()) out.add(s);
        }
        return out;
    }

    void close() {
        if (recognizer != null) {
            recognizer.close();
            recognizer = null;
        }
    }
}
