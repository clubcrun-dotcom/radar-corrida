package br.leno.radarcorrida;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.RandomAccessFile;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import br.leno.radarcorrida.core.Evaluation;
import br.leno.radarcorrida.core.HistoryParser;

/**
 * Registros locais, gravados na pasta privada do app (Android/data/br.leno.radarcorrida/files).
 *  - corridas.csv   : uma linha por oferta avaliada (separador ";" e vírgula decimal, abre no Excel BR)
 *  - telas.txt      : textos brutos das telas dos apps de corrida (modo diagnóstico)
 *  - descoberta.txt : nomes de pacotes que exibiram "R$" e "km" (modo descoberta)
 */
public final class RideLog {
    public static final String RIDES = "corridas.csv";
    public static final String SCREENS = "telas.txt";
    public static final String DISCOVERY = "descoberta.txt";
    public static final String HISTORY = "historico.csv";
    private java.util.Set<String> historyKeys;

    private static final long MAX_SCREENS_BYTES = 2L * 1024 * 1024;
    private static final String HEADER =
            "data_hora;app;perfil;posicao;ofertas_na_tela;valor;busca_km;busca_min;viagem_km;viagem_min;total_km;total_min;"
                    + "rs_km;rs_h;custo;lucro;lucro_pct;nota;veredito;no_trajeto;destino\n";

    private final File dir;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final SimpleDateFormat ts =
            new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
    private final Locale br = Locale.forLanguageTag("pt-BR");
    private int lastScreenHash;

    public RideLog(Context c) {
        File d = c.getExternalFilesDir(null);
        dir = d != null ? d : c.getFilesDir();
    }

    public File file(String name) { return new File(dir, name); }

    public void appendRide(String pkg, Evaluation e, int position, int total) {
        final String line = String.format(br,
                "%s;%s;%s;%d;%d;%.2f;%.2f;%.0f;%.2f;%.0f;%.2f;%.0f;%.2f;%.2f;%.2f;%.2f;%.1f;%.1f;%s;%s;%s\n",
                now(), pkg, clean(e.profileName), position, total, e.offer.fare, e.offer.pickupKm, e.offer.pickupMin,
                e.offer.tripKm, e.offer.tripMin, e.totalKm, e.totalMin,
                e.rsPerKm, e.rsPerHour, e.cost, e.profit, e.profitPct, e.score, e.verdict.name(),
                e.routeHit != null ? "sim" : "nao", clean(e.offer.destination));
        io.execute(() -> {
            File f = file(RIDES);
            boolean fresh = !f.exists() || f.length() == 0;
            write(f, (fresh ? HEADER : "") + line);
        });
    }

    /** Grava o texto bruto da tela, ignorando telas idênticas à anterior. */
    public void dumpScreen(String pkg, List<String> texts) {
        int h = (pkg + texts).hashCode();
        if (h == lastScreenHash) return;
        lastScreenHash = h;
        StringBuilder sb = new StringBuilder();
        sb.append("=== ").append(now()).append(" | ").append(pkg).append('\n');
        for (String t : texts) sb.append("  ").append(t.replace('\n', ' ')).append('\n');
        final String block = sb.toString();
        io.execute(() -> {
            File f = file(SCREENS);
            if (f.length() > MAX_SCREENS_BYTES) {
                File old = file("telas_anterior.txt");
                //noinspection ResultOfMethodCallIgnored
                old.delete();
                //noinspection ResultOfMethodCallIgnored
                f.renameTo(old);
            }
            write(file(SCREENS), block);
        });
    }

    public void discovered(String pkg) {
        final String line = now() + " " + pkg + "\n";
        io.execute(() -> write(file(DISCOVERY), line));
    }

    /** Últimos maxChars caracteres de um arquivo, para compartilhar como texto. */
    public String tail(String name, int maxChars) {
        File f = file(name);
        if (!f.exists()) return "";
        try (RandomAccessFile raf = new RandomAccessFile(f, "r")) {
            long len = raf.length();
            long start = Math.max(0, len - maxChars);
            byte[] buf = new byte[(int) (len - start)];
            raf.seek(start);
            raf.readFully(buf);
            String s = new String(buf, StandardCharsets.UTF_8);
            if (start > 0 && name.equals(RIDES)) s = HEADER + s.substring(s.indexOf('\n') + 1);
            return s;
        } catch (IOException e) {
            return "";
        }
    }

    /** Corridas do histórico já registradas (lido do arquivo). Chamar fora da thread principal se grande. */
    public java.util.List<HistoryParser.Trip> loadHistory() {
        return HistoryParser.fromCsv(readAll(HISTORY));
    }

    /** Grava só as corridas novas (sem repetir) e devolve quantas eram novas. */
    public synchronized int appendHistory(java.util.List<HistoryParser.Trip> trips) {
        if (historyKeys == null) {
            historyKeys = new java.util.HashSet<>();
            for (HistoryParser.Trip t : loadHistory()) historyKeys.add(t.key());
        }
        StringBuilder sb = new StringBuilder();
        int added = 0;
        for (HistoryParser.Trip t : trips) {
            if (historyKeys.add(t.key())) {
                sb.append(HistoryParser.toCsv(t));
                added++;
            }
        }
        if (added > 0) {
            final File f = file(HISTORY);
            final boolean fresh = !f.exists() || f.length() == 0;
            final String body = (fresh ? HistoryParser.CSV_HEADER : "") + sb;
            io.execute(() -> write(f, body));
        }
        return added;
    }

    private String readAll(String name) {
        File f = file(name);
        if (!f.exists()) return "";
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            byte[] buf = new byte[(int) f.length()];
            int off = 0, r;
            while (off < buf.length && (r = in.read(buf, off, buf.length - off)) > 0) off += r;
            return new String(buf, 0, off, StandardCharsets.UTF_8);
        } catch (IOException e) {
            return "";
        }
    }

    public void clearAll() {
        io.execute(() -> {
            for (String n : new String[]{RIDES, SCREENS, DISCOVERY, "telas_anterior.txt"}) {
                //noinspection ResultOfMethodCallIgnored
                file(n).delete();
            }
        });
    }

    private static String clean(String s) {
        return s == null ? "" : s.replace(';', ',').replace('\n', ' ');
    }

    private String now() {
        synchronized (ts) {
            return ts.format(new Date());
        }
    }

    private static void write(File f, String s) {
        try (Writer w = new OutputStreamWriter(new FileOutputStream(f, true), StandardCharsets.UTF_8)) {
            w.write(s);
        } catch (IOException ignored) {
            // Log é best-effort: nunca derruba o serviço.
        }
    }
}
