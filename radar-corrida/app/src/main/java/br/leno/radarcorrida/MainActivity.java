package br.leno.radarcorrida;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Bundle;
import android.provider.Settings;
import android.text.InputType;
import android.util.TypedValue;
import android.view.View;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.SeekBar;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import br.leno.radarcorrida.core.Evaluation;
import br.leno.radarcorrida.core.Offer;
import br.leno.radarcorrida.core.OfferParser;
import br.leno.radarcorrida.core.Profile;
import br.leno.radarcorrida.core.Ranker;

/** Tela única: status, carro, perfis e metas, simulador e exportação dos registros. */
public class MainActivity extends Activity {
    private LinearLayout col;
    private TextView status, costInfo, simOut;
    private EditText fuel, kmpl, extra, goal, hours, busy, pMinProfitH;
    private TextView goalInfo, dayInfo, realInfo, histBanner;
    private CheckBox pDetour;
    private RadioButton goalGross, goalNet;
    private java.util.List<br.leno.radarcorrida.core.GoalCheck.Seen> seenCache;
    private Spinner profileSpinner;
    private ArrayAdapter<String> profileAdapter;
    private EditText pName, pMinKm, pMinH, pMinProfit, pRoute, pMaxPickKm, pMaxPickMin;
    private RadioButton ruleAll, ruleAny;
    private CheckBox pIncludePickup;
    private EditText packages, simInput;
    private Slider sizeSlider, ySlider;
    private boolean loadingLayout;
    private CheckBox debug, discovery, ocrBox;
    private RadioButton layFull, layStrip, layColumn;

    private List<Profile> profiles;
    private int shown;
    private RideLog log;

    private TextView stepRestricted, stepEnable;
    private Button btnRestricted, btnEnable;
    private boolean built;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        if (!Prefs.sp(this).getBoolean(Prefs.K_CONSENT, false)) {
            showDisclosure();
        } else {
            buildMain();
        }
    }

    /**
     * Aviso de uso da acessibilidade, com aceite explícito, antes de qualquer ativação.
     * Exigido pela política da Google Play para apps que usam a API de acessibilidade.
     */
    private void showDisclosure() {
        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(20);
        col.setPadding(p, p, p, p);
        scroll.addView(col);
        setContentView(scroll);

        title("Antes de começar", 22);
        TextView t = new TextView(this);
        t.setTextSize(15);
        t.setLineSpacing(0, 1.2f);
        t.setText("O Radar de Corrida usa o serviço de Acessibilidade do Android para LER o texto "
                + "das ofertas que aparecem na tela da Uber, 99 e inDrive: valor, distância, tempo "
                + "e endereço de destino.\n\n"
                + "Quando o app de corrida não disponibiliza esse texto (caso da Uber), o Radar faz "
                + "uma CAPTURA DA TELA, só enquanto o app de corrida está aberto, e lê os números da "
                + "imagem no próprio aparelho. A imagem fica só na memória e é descartada na hora: "
                + "não é salva nem enviada.\n\n"
                + "• Isso é usado só para calcular R$/km, R$/hora e lucro e mostrar a etiqueta.\n"
                + "• Ele lê apenas os apps da lista de apps monitorados.\n"
                + "• Ele NÃO toca, aceita nem recusa corridas. A decisão é sempre sua.\n"
                + "• Nada é enviado para a internet: o app não tem permissão de internet. "
                + "Os registros ficam só neste aparelho e você pode apagá-los quando quiser.\n\n"
                + "Para funcionar, você vai ativar o Radar de Corrida em Configurações → Acessibilidade.");
        col.addView(t);
        button("Concordo e quero continuar", v -> {
            Prefs.sp(this).edit().putBoolean(Prefs.K_CONSENT, true).apply();
            buildMain();
            refreshStatus();
        });
        button("Agora não", v -> finish());
    }

    private void buildMain() {
        built = true;
        log = new RideLog(this);
        profiles = Prefs.profiles(this);
        shown = Prefs.activeIndex(this, profiles.size());

        ScrollView scroll = new ScrollView(this);
        scroll.setFitsSystemWindows(true);
        col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        col.setPadding(p, p, p, dp(48));
        scroll.addView(col);
        setContentView(scroll);

        title("Radar de Corrida", 24);
        TextView ver = new TextView(this);
        String vn = "?";
        try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) { /* sem versão */ }
        ver.setText("Versão " + vn);
        ver.setTextSize(13);
        ver.setTextColor(0xFF757575);
        col.addView(ver);
        status = new TextView(this);
        status.setTextSize(15);
        col.addView(status);
        stepRestricted = new TextView(this);
        stepRestricted.setText("Passo 1 (Android 13 ou mais novo): na tela que abrir, toque nos ⋮ "
                + "no canto de cima e em \"Permitir configurações restritas\". "
                + "Se não aparecer essa opção, pule para o passo 2.");
        stepRestricted.setPadding(0, dp(8), 0, 0);
        col.addView(stepRestricted);
        btnRestricted = new Button(this);
        btnRestricted.setText("Abrir informações do app");
        btnRestricted.setAllCaps(false);
        btnRestricted.setOnClickListener(v -> startActivity(new Intent(
                Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                android.net.Uri.parse("package:" + getPackageName()))));
        col.addView(btnRestricted);
        stepEnable = new TextView(this);
        stepEnable.setText("Passo 2: em Acessibilidade, toque em \"Radar de Corrida\" e ative.");
        stepEnable.setPadding(0, dp(8), 0, 0);
        col.addView(stepEnable);
        btnEnable = new Button(this);
        btnEnable.setText("Abrir Acessibilidade");
        btnEnable.setAllCaps(false);
        btnEnable.setOnClickListener(v ->
                startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        col.addView(btnEnable);

        // ---- Carro ----
        title("Carro (vale para todos os perfis)", 18);
        SharedPreferences sp = Prefs.sp(this);
        fuel = field("Combustível (R$/litro)", null, sp.getString(Prefs.K_FUEL, Prefs.DEF_FUEL));
        kmpl = field("Consumo médio real (km/litro)",
                "Use o consumo medido na bomba, não o do manual.", sp.getString(Prefs.K_KMPL, Prefs.DEF_KMPL));
        extra = field("Custo extra por km (R$/km)",
                "Manutenção, pneus, óleo, depreciação, seguro rateado. Sem isso o lucro sai otimista.",
                sp.getString(Prefs.K_EXTRA, Prefs.DEF_EXTRA));
        costInfo = new TextView(this);
        costInfo.setTextSize(14);
        costInfo.setPadding(0, dp(4), 0, 0);
        col.addView(costInfo);

        histBanner = new TextView(this);
        histBanner.setTextSize(14);
        histBanner.setPadding(dp(10), dp(8), dp(10), dp(8));
        histBanner.setBackgroundColor(0xFFFFF3E0);
        col.addView(histBanner);

        // ---- Meta do dia ----
        title("Meta do dia", 18);
        dayInfo = new TextView(this);
        dayInfo.setTextSize(15);
        dayInfo.setTypeface(Typeface.DEFAULT_BOLD);
        dayInfo.setLineSpacing(0, 1.15f);
        dayInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        dayInfo.setBackgroundColor(0xFFE3F2FD);
        col.addView(dayInfo);
        RadioGroup gk = new RadioGroup(this);
        goalGross = new RadioButton(this);
        goalGross.setText("Bruto: o que o app de corrida paga");
        goalNet = new RadioButton(this);
        goalNet.setText("Lucro líquido: já descontado o custo do carro");
        gk.addView(goalGross);
        gk.addView(goalNet);
        col.addView(gk);
        Profile gp = profiles.get(shown);
        boolean net = gp.hasGoal() ? gp.netGoal : sp.getBoolean(Prefs.K_NET, false);
        goalNet.setChecked(net);
        goalGross.setChecked(!net);
        goal = field("Meta por dia (R$) · deste perfil",
                "Cada perfil tem a sua meta. Trocou o perfil lá embaixo, a meta troca junto.",
                gp.hasGoal() ? Prefs.edit(gp.dailyGoal) : sp.getString(Prefs.K_GOAL, "100"));
        hours = field("Horas disponíveis por dia", null,
                gp.hasGoal() && !Double.isNaN(gp.dailyHours) ? Prefs.edit(gp.dailyHours) : sp.getString(Prefs.K_HOURS, "3"));
        busy = field("Tempo com passageiro (%)",
                "Parte do tempo online que você está em corrida (indo buscar + levando). "
                        + "O resto é espera e volta, sem ganho. 60% é um bom ponto de partida.",
                gp.hasGoal() && !Double.isNaN(gp.busyPct) ? Prefs.edit(gp.busyPct) : sp.getString(Prefs.K_BUSY, "60"));
        goalInfo = new TextView(this);
        goalInfo.setTextSize(14);
        goalInfo.setLineSpacing(0, 1.15f);
        goalInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        col.addView(goalInfo);
        button("Usar esse valor como meta por hora do perfil", v -> {
            br.leno.radarcorrida.core.Settings g = liveGoal();
            String val = Prefs.edit(Math.ceil(g.neededProfitPerHourPerRide()));
            if (g.netGoal) {
                pMinProfitH.setText(val);
                pMinH.setText("");
            } else {
                pMinH.setText(val);
                pMinProfitH.setText("");
            }
            save(true);
        });
        gk.setOnCheckedChangeListener((group, id) -> updateCostInfo());

        // ---- Números reais ----
        title("Seus números reais (histórico da Uber)", 18);
        realInfo = new TextView(this);
        realInfo.setTextSize(14);
        realInfo.setLineSpacing(0, 1.15f);
        realInfo.setPadding(dp(10), dp(8), dp(10), dp(8));
        realInfo.setBackgroundColor(0xFFF1F8E9);
        col.addView(realInfo);

        // ---- Perfil ----
        title("Perfil de motorista", 18);
        profileSpinner = new Spinner(this);
        profileAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, new ArrayList<>());
        profileAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        profileSpinner.setAdapter(profileAdapter);
        col.addView(profileSpinner);
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.addView(smallButton("Novo perfil", v -> newProfile()));
        row.addView(smallButton("Excluir perfil", v -> deleteProfile()));
        col.addView(row);

        pName = field("Nome do perfil", null, "");
        hint("Regra de aceite");
        RadioGroup rg = new RadioGroup(this);
        ruleAll = new RadioButton(this);
        ruleAll.setText("Bater TODAS as metas ligadas (tempo integral)");
        ruleAny = new RadioButton(this);
        ruleAny.setText("Bater QUALQUER uma das metas (renda extra, trajeto)");
        rg.addView(ruleAll);
        rg.addView(ruleAny);
        col.addView(rg);

        hint("Metas — deixe em branco para desligar");
        pMinProfitH = field("Lucro mínimo por hora (R$/h)",
                "Lucro limpo ÷ tempo da corrida (busca + viagem). É o que paga o seu tempo.", "");
        pMinKm = field("Mínimo R$/km", "Valor ÷ km total. Remunera o desgaste do carro.", "");
        pMinH = field("Mínimo R$/hora (bruto)", "Valor ÷ tempo total (busca + viagem).", "");
        pMinProfit = field("Lucro mínimo (%)",
                "(Valor − custo por km × km) ÷ valor. Mostra quanto sobra de cada real.", "");
        pRoute = field("Bairros/cidades do seu trajeto",
                "Um por linha. Se o destino tiver todas as palavras da linha, conta como meta batida. "
                        + "Ex.: \"Centro Rio de Janeiro\" não confunde com o Centro de São Gonçalo.", "");
        pRoute.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        pRoute.setMinLines(2);

        hint("Filtros eliminatórios — recusa sempre que passar do limite");
        pMaxPickKm = field("Busca máxima (km até o passageiro)", null, "");
        pMaxPickMin = field("Busca máxima (minutos até o passageiro)", null, "");
        pDetour = new CheckBox(this);
        pDetour.setText("No trajeto (casa ↔ trabalho), contar só o custo do desvio até o passageiro");
        col.addView(pDetour);
        pIncludePickup = new CheckBox(this);
        pIncludePickup.setText("Somar o deslocamento até o passageiro no km e no tempo");
        col.addView(pIncludePickup);

        button("Salvar", v -> save(true));

        profileSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override
            public void onItemSelected(AdapterView<?> parent, View view, int pos, long id) {
                if (pos == shown) return;
                collectProfile(profiles.get(shown));
                shown = pos;
                showProfile(profiles.get(shown));
                Prefs.saveProfiles(MainActivity.this, profiles, shown);
                refreshNames();
                updateCostInfo();
            }

            @Override
            public void onNothingSelected(AdapterView<?> parent) {}
        });

        // ---- Etiqueta na tela ----
        title("Etiqueta na tela", 18);
        hint("Escolha o formato e ajuste tamanho e altura. A cada ajuste a etiqueta aparece por 6 s "
                + "com uma corrida de exemplo, para você ver como fica. Cada formato guarda o seu "
                + "tamanho e a sua altura.");
        RadioGroup lay = new RadioGroup(this);
        layStrip = new RadioButton(this);
        layStrip.setText("Faixa compacta (2 linhas)");
        layColumn = new RadioButton(this);
        layColumn.setText("Coluna à direita, dentro do cartão da Uber (mapa livre)");
        layFull = new RadioButton(this);
        layFull.setText("Cartão completo (4 quadros, destino e rodapé)");
        lay.addView(layStrip);
        lay.addView(layColumn);
        lay.addView(layFull);
        col.addView(lay);
        int lm = sp.getInt(Prefs.K_LAYOUT, 1);
        (lm == 0 ? layFull : lm == 2 ? layColumn : layStrip).setChecked(true);
        sizeSlider = new Slider("Tamanho", "%", Prefs.SCALE_MIN, Prefs.SCALE_MAX, 5,
                v -> commitLayout(Prefs.scaleKey(layoutMode()), v));
        ySlider = new Slider("Altura (distância do topo)", " dp", 0, 900, 5,
                v -> commitLayout(Prefs.yKey(layoutMode()), v));
        hint("Coluna: suba ou desça até o topo da coluna ficar logo abaixo do X do cartão da Uber. "
                + "Faixa: 30–40 cola no topo da tela.");
        loadLayoutBars();
        lay.setOnCheckedChangeListener((g, id) -> {
            Prefs.sp(this).edit().putInt(Prefs.K_LAYOUT, layoutMode()).apply();
            loadLayoutBars();
            preview();
        });
        button("Mostrar prévia da etiqueta", v -> preview());

        // ---- Simulador ----
        title("Simulador", 18);
        hint("Cole o texto de uma oferta (ou um bloco do telas.txt) e veja o cálculo com o perfil ativo.");
        simInput = new EditText(this);
        simInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        simInput.setMinLines(4);
        simInput.setText("R$ 23,94\n5 min (1,8 km) de distância\nRua Doutor Lopes, São Gonçalo\n"
                + "Viagem de 23 min (13,3 km)\nAv. Amaral Peixoto, Niterói");
        col.addView(simInput);
        button("Calcular", v -> simulate());
        simOut = new TextView(this);
        simOut.setTextSize(15);
        simOut.setTextIsSelectable(true);
        col.addView(simOut);

        // ---- Diagnóstico ----
        title("Teste e diagnóstico", 18);
        ocrBox = check("Leitura por imagem (necessária para a Uber; usa um pouco mais de bateria)",
                sp.getBoolean(Prefs.K_OCR, true));
        debug = check("Modo diagnóstico: gravar textos das telas dos apps de corrida",
                sp.getBoolean(Prefs.K_DEBUG, true));
        discovery = check("Modo descoberta: anotar nomes de apps que mostram R$ e km",
                sp.getBoolean(Prefs.K_DISCOVERY, false));
        packages = field("Apps monitorados (um pacote por linha)", null,
                sp.getString(Prefs.K_PACKAGES, Prefs.DEFAULT_PACKAGES));
        packages.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        packages.setMinLines(3);
        button("Salvar diagnóstico", v -> save(true));

        // ---- Registros ----
        title("Registros", 18);
        button("Compartilhar corridas.csv", v -> share(RideLog.RIDES, 200_000));
        button("Compartilhar telas.txt (diagnóstico)", v -> share(RideLog.SCREENS, 90_000));
        button("Compartilhar descoberta.txt", v -> share(RideLog.DISCOVERY, 20_000));
        button("Compartilhar historico.csv (corridas reais)", v -> share(RideLog.HISTORY, 200_000));
        button("Apagar registros", v -> {
            log.clearAll();
            toast("Registros apagados");
        });
        hint("Arquivos ficam em: " + log.file("").getAbsolutePath());

        refreshNames();
        showProfile(profiles.get(shown));
        updateCostInfo();

        android.text.TextWatcher live = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void onTextChanged(CharSequence c, int a, int b, int d) {}
            @Override public void afterTextChanged(android.text.Editable e) { updateCostInfo(); }
        };
        fuel.addTextChangedListener(live);
        kmpl.addTextChangedListener(live);
        extra.addTextChangedListener(live);
        pMinProfit.addTextChangedListener(live);
        pMinKm.addTextChangedListener(live);
        goal.addTextChangedListener(live);
        hours.addTextChangedListener(live);
        busy.addTextChangedListener(live);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refreshStatus();
    }

    private void refreshStatus() {
        if (!built) return;
        seenCache = null;   // relê as ofertas do turno ao voltar para o app
        updateCostInfo();
        br.leno.radarcorrida.core.Settings st = Prefs.settings(this);
        java.util.List<br.leno.radarcorrida.core.HistoryParser.Trip> hist = log.loadHistory();
        realInfo.setText(br.leno.radarcorrida.core.RealStats.of(hist, st).text(st));
        String last = Prefs.sp(this).getString(Prefs.K_HIST_LAST, "");
        if (Prefs.today().equals(last)) {
            histBanner.setVisibility(View.GONE);
        } else {
            histBanner.setVisibility(View.VISIBLE);
            histBanner.setText("📋 Lembrete: na Uber, abra Ganhos → Histórico de ganhos e role a lista até "
                    + "as corridas mais recentes. O Radar registra sozinho"
                    + (last.isEmpty() ? "." : " (último registro: " + last + ")."));
        }
        br.leno.radarcorrida.core.DayTracker d = Prefs.loadDay(this);
        double goalV = Prefs.settings(this).dailyGoal;
        dayInfo.setText(Prefs.today().equals(d.day) ? d.summary(goalV)
                : "Hoje: ainda sem leitura. Abra a Uber e fique online para o Radar acompanhar a meta.");
        boolean on = serviceEnabled();
        status.setText(on
                ? "● Ativo — a etiqueta aparece quando chegar uma oferta."
                : "○ Desativado — faça os 2 passos abaixo (leva 30 segundos).");
        status.setTextColor(on ? 0xFF2E7D32 : 0xFFC62828);
        int vis = on ? View.GONE : View.VISIBLE;
        boolean needsRestricted = android.os.Build.VERSION.SDK_INT >= 33;
        stepRestricted.setVisibility(on || !needsRestricted ? View.GONE : View.VISIBLE);
        btnRestricted.setVisibility(on || !needsRestricted ? View.GONE : View.VISIBLE);
        stepEnable.setVisibility(vis);
        btnEnable.setVisibility(vis);
        if (!needsRestricted) stepEnable.setText("Em Acessibilidade, toque em \"Radar de Corrida\" e ative.");
    }

    // ---- Perfis ----

    private void showProfile(Profile p) {
        pName.setText(p.name);
        ruleAll.setChecked(p.matchAll);
        ruleAny.setChecked(!p.matchAll);
        pMinKm.setText(Prefs.edit(p.minRsPerKm));
        pMinH.setText(Prefs.edit(p.minRsPerHour));
        pMinProfitH.setText(Prefs.edit(p.minProfitPerHour));
        pMinProfit.setText(Prefs.edit(p.minProfitPct));
        StringBuilder kw = new StringBuilder();
        for (String k : p.routeKeywords) kw.append(kw.length() > 0 ? "\n" : "").append(k);
        pRoute.setText(kw.toString());
        pMaxPickKm.setText(Prefs.edit(p.maxPickupKm));
        pMaxPickMin.setText(Prefs.edit(p.maxPickupMin));
        pIncludePickup.setChecked(p.includePickup);
        pDetour.setChecked(p.routeDetourCostOnly);
        if (goal != null && p.hasGoal()) {
            goal.setText(Prefs.edit(p.dailyGoal));
            if (!Double.isNaN(p.dailyHours)) hours.setText(Prefs.edit(p.dailyHours));
            if (!Double.isNaN(p.busyPct)) busy.setText(Prefs.edit(p.busyPct));
            goalNet.setChecked(p.netGoal);
            goalGross.setChecked(!p.netGoal);
        }
    }

    private void collectProfile(Profile p) {
        String n = pName.getText().toString().trim();
        p.name = n.isEmpty() ? "Perfil" : n;
        p.matchAll = !ruleAny.isChecked();
        p.minRsPerKm = Prefs.num(pMinKm.getText().toString(), 0);
        p.minRsPerHour = Prefs.num(pMinH.getText().toString(), 0);
        p.minProfitPerHour = Prefs.num(pMinProfitH.getText().toString(), 0);
        p.minProfitPct = Math.min(99, Prefs.num(pMinProfit.getText().toString(), 0));
        p.routeKeywords = Profile.parseKeywords(pRoute.getText().toString());
        p.maxPickupKm = Prefs.num(pMaxPickKm.getText().toString(), 0);
        p.maxPickupMin = Prefs.num(pMaxPickMin.getText().toString(), 0);
        p.includePickup = pIncludePickup.isChecked();
        p.routeDetourCostOnly = pDetour.isChecked();
        if (goal != null) {
            p.dailyGoal = Prefs.num(goal.getText().toString(), Double.isNaN(p.dailyGoal) ? 100 : p.dailyGoal);
            p.dailyHours = Prefs.num(hours.getText().toString(), Double.isNaN(p.dailyHours) ? 3 : p.dailyHours);
            p.busyPct = Prefs.num(busy.getText().toString(), Double.isNaN(p.busyPct) ? 60 : p.busyPct);
            p.netGoal = goalNet.isChecked();
        }
    }

    private void newProfile() {
        collectProfile(profiles.get(shown));
        Profile p = profiles.get(shown).copy();
        p.name = "Novo perfil";
        profiles.add(p);
        shown = profiles.size() - 1;
        Prefs.saveProfiles(this, profiles, shown);
        refreshNames();
        showProfile(p);
        toast("Perfil criado a partir do atual. Ajuste e salve.");
    }

    private void deleteProfile() {
        if (profiles.size() <= 1) {
            toast("Precisa existir pelo menos um perfil");
            return;
        }
        profiles.remove(shown);
        shown = 0;
        Prefs.saveProfiles(this, profiles, shown);
        refreshNames();
        showProfile(profiles.get(shown));
        updateCostInfo();
    }

    private void refreshNames() {
        List<String> names = new ArrayList<>();
        for (Profile p : profiles) names.add(p.name);
        profileAdapter.clear();
        profileAdapter.addAll(names);
        profileAdapter.notifyDataSetChanged();
        profileSpinner.setSelection(shown);
    }

    // ---- Salvar / informação de custo ----

    private void save(boolean notify) {
        SharedPreferences.Editor e = Prefs.sp(this).edit();
        e.putString(Prefs.K_FUEL, fuel.getText().toString());
        e.putString(Prefs.K_KMPL, kmpl.getText().toString());
        e.putString(Prefs.K_EXTRA, extra.getText().toString());
        e.putString(Prefs.K_GOAL, goal.getText().toString());
        e.putString(Prefs.K_HOURS, hours.getText().toString());
        e.putString(Prefs.K_BUSY, busy.getText().toString());
        e.putBoolean(Prefs.K_NET, goalNet.isChecked());
        e.putString(Prefs.K_PACKAGES, packages.getText().toString());
        e.putBoolean(Prefs.K_DEBUG, debug.isChecked());
        e.putBoolean(Prefs.K_DISCOVERY, discovery.isChecked());
        e.putInt(Prefs.K_LAYOUT, layoutMode());
        e.putBoolean(Prefs.K_OCR, ocrBox.isChecked());
        e.apply();
        collectProfile(profiles.get(shown));
        Prefs.saveProfiles(this, profiles, shown);
        refreshNames();
        updateCostInfo();
        if (notify) toast("Salvo · perfil ativo: " + profiles.get(shown).name);
    }

    /** Recalcula a linha de custo a partir do que está digitado, sem precisar salvar. */
    private void updateCostInfo() {
        br.leno.radarcorrida.core.Settings s = Prefs.settings(this);
        s.fuelPrice = Prefs.num(fuel.getText().toString(), s.fuelPrice);
        s.kmPerLiter = Prefs.num(kmpl.getText().toString(), s.kmPerLiter);
        s.extraCostPerKm = Prefs.num(extra.getText().toString(), s.extraCostPerKm);
        if (pMinProfit != null) s.profile.minProfitPct = Math.min(99, Prefs.num(pMinProfit.getText().toString(), 0));
        if (pMinKm != null) s.profile.minRsPerKm = Prefs.num(pMinKm.getText().toString(), 0);
        StringBuilder sb = new StringBuilder();
        sb.append("Custo atual: R$ ").append(Prefs.fmt(s.costPerKm())).append("/km");
        double pct = s.profile.minProfitPct;
        if (pct > 0) {
            sb.append("\nLucro de ").append(Prefs.edit(pct)).append("% exige a corrida pagar ≥ R$ ")
                    .append(Prefs.fmt(s.breakEvenRsPerKm(pct))).append("/km");
        }
        if (s.profile.minRsPerKm > 0) {
            double margin = (1 - s.costPerKm() / s.profile.minRsPerKm) * 100;
            sb.append("\nNa meta de R$ ").append(Prefs.fmt(s.profile.minRsPerKm))
                    .append("/km sobram ").append(Prefs.edit(Math.round(margin))).append("% de cada real");
        }
        costInfo.setText(sb.toString());

        if (goalInfo != null) {
            br.leno.radarcorrida.core.Settings g = liveGoal();
            if (seenCache == null) {
                seenCache = br.leno.radarcorrida.core.GoalCheck.fromCsv(log.tail(RideLog.RIDES, 400_000));
            }
            br.leno.radarcorrida.core.GoalCheck c =
                    br.leno.radarcorrida.core.GoalCheck.run(g, g.netGoal, seenCache);
            goalInfo.setText("CHECAGEM DE REALIDADE\n" + c.text());
            int bg;
            switch (c.status) {
                case OK: bg = 0xFFE8F5E9; break;
                case TIGHT: bg = 0xFFFFF8E1; break;
                case UNLIKELY: bg = 0xFFFFEBEE; break;
                default: bg = 0xFFF1F3F4; break;
            }
            goalInfo.setBackgroundColor(bg);
        }
    }

    private br.leno.radarcorrida.core.Settings liveGoal() {
        br.leno.radarcorrida.core.Settings g = Prefs.settings(this);
        g.dailyGoal = Prefs.num(goal.getText().toString(), g.dailyGoal);
        g.dailyHours = Prefs.num(hours.getText().toString(), g.dailyHours);
        g.busyPct = Prefs.num(busy.getText().toString(), g.busyPct);
        g.netGoal = goalNet.isChecked();
        return g;
    }

    // ---- Simulador ----

    private void simulate() {
        save(false);
        String raw = simInput.getText().toString();
        List<String> lines = Arrays.asList(raw.split("\\r?\\n"));
        List<Offer> offers = OfferParser.parseAll(lines);
        if (offers.isEmpty()) {
            simOut.setText("Não reconheci uma oferta nesse texto. Ele precisa ter um valor em R$ "
                    + "e ao menos um par de tempo e distância, ex.: \"23 min (13,3 km)\".");
            return;
        }
        List<Evaluation> ranked = Ranker.evaluateAndRank(offers, Prefs.settings(this));
        StringBuilder sb = new StringBuilder();
        if (ranked.size() > 1) {
            sb.append(ranked.size()).append(" ofertas na tela — da melhor para a pior:\n");
            for (int i = 0; i < ranked.size(); i++) {
                sb.append(i + 1).append("º ").append(ranked.get(i).compactLine()).append('\n');
            }
            sb.append("\nDetalhe da melhor:\n");
        }
        Evaluation ev = ranked.get(0);
        Offer o = ev.offer;
        sb.append(ev.verdictLabel()).append("  [").append(ev.profileName).append("]\n")
                .append(ev.headline()).append('\n')
                .append(ev.details()).append("\n\n");
        for (String r : ev.reasons) sb.append(r).append('\n');
        sb.append("\nLido: valor R$ ").append(Prefs.fmt(o.fare))
                .append(" | busca ").append(Prefs.fmt(o.pickupKm)).append(" km / ").append((int) o.pickupMin).append(" min")
                .append(" | viagem ").append(Prefs.fmt(o.tripKm)).append(" km / ").append((int) o.tripMin).append(" min")
                .append("\nDestino lido: ").append(o.destination.isEmpty() ? "(não encontrado)" : o.destination)
                .append("\nCusto R$ ").append(Prefs.fmt(ev.cost))
                .append(" · lucro R$ ").append(Prefs.fmt(ev.profit))
                .append(" · lucro/h R$ ").append(Prefs.fmt(ev.profitPerHour));
        simOut.setText(sb.toString());
    }

    // ---- Registros ----

    private void share(String name, int maxChars) {
        String body = log.tail(name, maxChars);
        if (body.isEmpty()) {
            toast(name + " ainda está vazio");
            return;
        }
        Intent i = new Intent(Intent.ACTION_SEND);
        i.setType("text/plain");
        i.putExtra(Intent.EXTRA_SUBJECT, "Radar de Corrida — " + name);
        i.putExtra(Intent.EXTRA_TEXT, body);
        startActivity(Intent.createChooser(i, "Enviar " + name));
    }

    private boolean serviceEnabled() {
        String enabled = Settings.Secure.getString(getContentResolver(),
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (enabled == null) return false;
        String me = new ComponentName(this, RadarService.class).flattenToString();
        String meShort = new ComponentName(this, RadarService.class).flattenToShortString();
        for (String s : enabled.split(":")) {
            if (s.equalsIgnoreCase(me) || s.equalsIgnoreCase(meShort)) return true;
        }
        return false;
    }

    // ---- Etiqueta: formato, tamanho e altura ----

    private int layoutMode() {
        return layFull.isChecked() ? 0 : layColumn.isChecked() ? 2 : 1;
    }

    private void loadLayoutBars() {
        loadingLayout = true;
        int m = layoutMode();
        sizeSlider.set(Prefs.overlayScalePct(this, m));
        ySlider.set(Prefs.overlayYdp(this, m));
        loadingLayout = false;
    }

    private void commitLayout(String key, int value) {
        if (loadingLayout) return;
        Prefs.sp(this).edit().putInt(Prefs.K_LAYOUT, layoutMode()).putInt(key, value).apply();
        preview();
    }

    /** Pede ao serviço para mostrar a etiqueta de exemplo (aparece por cima desta tela). */
    private void preview() {
        if (!serviceEnabled()) {
            toast("Ative o Radar na Acessibilidade para ver a prévia");
            return;
        }
        Prefs.sp(this).edit().putLong(Prefs.K_PREVIEW, System.currentTimeMillis()).apply();
    }

    private interface IntSink { void accept(int v); }

    /** Barra com − e +: arrastar para ajuste grosso, tocar nos botões para ajuste fino. */
    private final class Slider {
        final String name, unit;
        final int min, step;
        final TextView label;
        final SeekBar bar;
        final IntSink onCommit;

        Slider(String name, String unit, int min, int max, int step, IntSink onCommit) {
            this.name = name;
            this.unit = unit;
            this.min = min;
            this.step = step;
            this.onCommit = onCommit;
            LinearLayout row = new LinearLayout(MainActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            row.setPadding(0, dp(8), 0, 0);
            Button minus = new Button(MainActivity.this);
            minus.setText("−");
            Button plus = new Button(MainActivity.this);
            plus.setText("+");
            LinearLayout.LayoutParams bl = new LinearLayout.LayoutParams(dp(56),
                    LinearLayout.LayoutParams.WRAP_CONTENT);
            minus.setLayoutParams(bl);
            plus.setLayoutParams(new LinearLayout.LayoutParams(bl));
            label = new TextView(MainActivity.this);
            label.setTextSize(16);
            label.setTypeface(Typeface.DEFAULT_BOLD);
            label.setGravity(android.view.Gravity.CENTER);
            label.setLayoutParams(new LinearLayout.LayoutParams(0,
                    LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
            row.addView(minus);
            row.addView(label);
            row.addView(plus);
            col.addView(row);
            bar = new SeekBar(MainActivity.this);
            bar.setMax((max - min) / step);
            col.addView(bar);
            minus.setOnClickListener(v -> { set(value() - step); commit(); });
            plus.setOnClickListener(v -> { set(value() + step); commit(); });
            bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
                @Override public void onProgressChanged(SeekBar b, int p, boolean user) { show(); }
                @Override public void onStartTrackingTouch(SeekBar b) {}
                @Override public void onStopTrackingTouch(SeekBar b) { commit(); }
            });
        }

        int value() { return min + bar.getProgress() * step; }

        void set(int v) {
            int p = Math.round((v - min) / (float) step);
            bar.setProgress(Math.max(0, Math.min(bar.getMax(), p)));
            show();
        }

        void show() { label.setText(name + ": " + value() + unit); }

        void commit() { onCommit.accept(value()); }
    }

    // ---- UI helpers ----

    private void title(String text, int sp) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(sp);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        t.setPadding(0, dp(18), 0, dp(6));
        col.addView(t);
    }

    private void hint(String text) {
        TextView t = new TextView(this);
        t.setText(text);
        t.setTextSize(13);
        t.setAlpha(0.7f);
        t.setPadding(0, dp(8), 0, 0);
        col.addView(t);
    }

    /** Campo de texto livre (aceita vírgula decimal em qualquer teclado). */
    private EditText field(String label, String help, String value) {
        TextView l = new TextView(this);
        l.setText(label);
        l.setTextSize(14);
        l.setPadding(0, dp(6), 0, 0);
        col.addView(l);
        if (help != null) {
            TextView h = new TextView(this);
            h.setText(help);
            h.setTextSize(12);
            h.setAlpha(0.65f);
            col.addView(h);
        }
        EditText e = new EditText(this);
        e.setText(value);
        e.setInputType(InputType.TYPE_CLASS_TEXT);
        col.addView(e);
        return e;
    }

    private CheckBox check(String label, boolean value) {
        CheckBox c = new CheckBox(this);
        c.setText(label);
        c.setChecked(value);
        col.addView(c);
        return c;
    }

    private void button(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        col.addView(b);
    }

    private Button smallButton(String text, View.OnClickListener l) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setOnClickListener(l);
        b.setLayoutParams(new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        return b;
    }

    private void toast(String s) {
        Toast.makeText(this, s, Toast.LENGTH_SHORT).show();
    }

    private int dp(int v) {
        return Math.round(TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v,
                getResources().getDisplayMetrics()));
    }
}
