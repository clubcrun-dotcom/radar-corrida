package br.leno.radarcorrida.core;

/** Uma oferta de corrida extraída da tela. Distâncias em km, tempos em minutos. */
public final class Offer {
    public final double fare;
    public final double pickupKm;
    public final double pickupMin;
    public final double tripKm;
    public final double tripMin;
    /** Texto do destino como aparece na tela (pode vir vazio se o app não mostrar). */
    public final String destination;
    /** true se a busca não foi lida na tela e saiu da conta "total da Uber − viagem". */
    public boolean pickupEstimated;

    public Offer(double fare, double pickupKm, double pickupMin, double tripKm, double tripMin) {
        this(fare, pickupKm, pickupMin, tripKm, tripMin, "");
    }

    public Offer(double fare, double pickupKm, double pickupMin, double tripKm, double tripMin,
                 String destination) {
        this.fare = fare;
        this.pickupKm = pickupKm;
        this.pickupMin = pickupMin;
        this.tripKm = tripKm;
        this.tripMin = tripMin;
        this.destination = destination == null ? "" : destination;
    }

    /** Assinatura usada para não reprocessar a mesma oferta a cada evento de tela. */
    /** Igual à assinatura, mas sem o destino: a leitura por imagem às vezes corta o fim do endereço. */
    public String numbersKey() {
        return String.format(java.util.Locale.ROOT, "%.2f|%.2f|%.0f|%.2f|%.0f",
                fare, pickupKm, pickupMin, tripKm, tripMin);
    }

    public String signature() {
        return String.format(java.util.Locale.ROOT, "%.2f|%.2f|%.0f|%.2f|%.0f|%s",
                fare, pickupKm, pickupMin, tripKm, tripMin, destination);
    }

    @Override
    public String toString() {
        return "Offer{fare=" + fare + ", pickup=" + pickupKm + "km/" + pickupMin
                + "min, trip=" + tripKm + "km/" + tripMin + "min, dest='" + destination + "'}";
    }
}
