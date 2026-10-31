package br.leno.radarcorrida.core;

/** Custos do carro (comuns a todos os perfis) + perfil ativo. Valores em R$. */
public final class Settings {
    /** Preço do combustível, R$/litro. */
    public double fuelPrice = 6.45;
    /** Consumo médio, km/litro. */
    public double kmPerLiter = 14.0;
    /** Custo extra por km (manutenção, pneus, depreciação, seguro rateado), R$/km. */
    public double extraCostPerKm = 0.0;
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
