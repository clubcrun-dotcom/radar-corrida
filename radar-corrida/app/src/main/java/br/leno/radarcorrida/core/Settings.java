package br.leno.radarcorrida.core;

/** Custos do carro (comuns a todos os perfis) + perfil ativo. Valores em R$. */
public final class Settings {
    /** Preço do combustível, R$/litro. */
    public double fuelPrice = 6.45;
    /** Consumo médio, km/litro. */
    public double kmPerLiter = 14.0;
    /** Custo extra por km (manutenção, pneus, depreciação, seguro rateado), R$/km. */
    public double extraCostPerKm = 0.0;
    /** Meta do dia (R$ de lucro), horas disponíveis e % do tempo online com passageiro. */
    public double dailyGoal = 100;
    public double dailyHours = 3;
    public double busyPct = 60;
    /** false = meta em faturamento BRUTO; true = meta em LUCRO LÍQUIDO (descontado o carro). */
    public boolean netGoal = false;

    /** Lucro/hora médio necessário no dia todo (contando o tempo parado). */
    public double neededProfitPerHourOverall() {
        return dailyHours > 0 ? dailyGoal / dailyHours : 0;
    }

    /**
     * Lucro/hora que cada corrida precisa render, enquanto dura, para fechar a meta do dia:
     * entre uma corrida e outra há tempo esperando e voltando, sem ganhar nada.
     */
    public double neededProfitPerHourPerRide() {
        double busy = Math.max(10, Math.min(100, busyPct)) / 100.0;
        return neededProfitPerHourOverall() / busy;
    }

    /** Estratégia de aceite em uso. */
    public Profile profile = Profile.fullTime();

    public double costPerKm() {
        double fuel = kmPerLiter > 0 ? fuelPrice / kmPerLiter : 0;
        return fuel + extraCostPerKm;
    }

    /** R$/km que a corrida precisa pagar para deixar o lucro % desejado. */
    public double breakEvenRsPerKm(double profitPct) {
        if (profitPct >= 100) return Double.POSITIVE_INFINITY;
        return costPerKm() / (1.0 - profitPct / 100.0);
    }
}
