package com.pegasuscorp.orbe.orion;

/**
 * Diff ligne à ligne simple (lecture mobile) — pas un Myers complet.
 */
public final class OrionTextDiff {

    private static final int MAX_LINES = 400;

    private OrionTextDiff() {}

    /**
     * @param baseline contenu précédent (dernier push / remote)
     * @param current  contenu local actuel
     */
    public static String unified(String path, String baseline, String current) {
        String a = baseline == null ? "" : baseline;
        String b = current == null ? "" : current;
        if (a.equals(b)) {
            return "Aucun changement" + (path != null && !path.isEmpty()
                    ? " dans « " + path + " »."
                    : ".");
        }
        String[] aa = a.split("\n", -1);
        String[] bb = b.split("\n", -1);
        StringBuilder sb = new StringBuilder();
        sb.append("--- dernier push\n+++ local");
        if (path != null && !path.isEmpty()) {
            sb.append(" (").append(path).append(')');
        }
        sb.append('\n');

        int i = 0;
        int j = 0;
        int emitted = 0;
        while ((i < aa.length || j < bb.length) && emitted < MAX_LINES) {
            if (i < aa.length && j < bb.length && aa[i].equals(bb[j])) {
                sb.append("  ").append(aa[i]).append('\n');
                i++;
                j++;
                emitted++;
                continue;
            }
            // Cherche si la ligne locale apparaît plus loin dans baseline
            int lookA = indexOf(aa, i, j < bb.length ? bb[j] : null, 8);
            int lookB = indexOf(bb, j, i < aa.length ? aa[i] : null, 8);
            if (lookA >= 0 && (lookB < 0 || lookA - i <= lookB - j)) {
                while (i < lookA && emitted < MAX_LINES) {
                    sb.append("- ").append(aa[i++]).append('\n');
                    emitted++;
                }
            } else if (lookB >= 0) {
                while (j < lookB && emitted < MAX_LINES) {
                    sb.append("+ ").append(bb[j++]).append('\n');
                    emitted++;
                }
            } else {
                if (i < aa.length && emitted < MAX_LINES) {
                    sb.append("- ").append(aa[i++]).append('\n');
                    emitted++;
                }
                if (j < bb.length && emitted < MAX_LINES) {
                    sb.append("+ ").append(bb[j++]).append('\n');
                    emitted++;
                }
            }
        }
        if (i < aa.length || j < bb.length) {
            sb.append("… (diff tronqué)\n");
        }
        return sb.toString().trim();
    }

    private static int indexOf(String[] arr, int from, String needle, int window) {
        if (needle == null || from >= arr.length) return -1;
        int end = Math.min(arr.length, from + window);
        for (int k = from; k < end; k++) {
            if (needle.equals(arr[k])) return k;
        }
        return -1;
    }
}
