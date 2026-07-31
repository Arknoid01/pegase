package com.pegasuscorp.orbe.intentions.location;

/**
 * Lieu nommé enregistré localement (maison, travail, restaurant…).
 */
public final class SavedPlace {

    public enum Type {
        HOME,
        WORK,
        RESTAURANT,
        OTHER;

        public String situationLabelFr() {
            switch (this) {
                case HOME: return "maison";
                case WORK: return "travail";
                case RESTAURANT: return "restaurant";
                default: return "lieu";
            }
        }
    }

    public final String id;
    public final String label;
    public final double lat;
    public final double lon;
    public final float radiusM;
    public final Type type;

    public SavedPlace(String id, String label, double lat, double lon, float radiusM, Type type) {
        this.id = id != null ? id : "";
        this.label = label != null ? label.trim() : "";
        this.lat = lat;
        this.lon = lon;
        this.radiusM = radiusM > 0 ? radiusM : 120f;
        this.type = type != null ? type : Type.OTHER;
    }

    /** Libellé pour le prompt situationnel. */
    public String situationLine() {
        if (!label.isEmpty()) {
            return type.situationLabelFr() + " · " + label;
        }
        return type.situationLabelFr();
    }

    /** Terme additionnel pour le scoring RAG. */
    public String searchTerm() {
        if (!label.isEmpty()) return label;
        return type.situationLabelFr();
    }

    public boolean contains(double pointLat, double pointLon) {
        return LocationSituationReader.distanceM(lat, lon, pointLat, pointLon) <= radiusM;
    }
}
