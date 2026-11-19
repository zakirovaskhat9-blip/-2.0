package dev.lt6.client;

/** Six newly supplied unfolded 3x2 panoramas, bundled as cubemap faces. */
public final class SkyLib {
    private SkyLib() {}
    public static final class Def {
        public final String name, pano;
        private final int color;
        Def(String name, String pano, int color) { this.name=name; this.pano=pano; this.color=color; }
        public int sky() { return color; }
        public int fog() { return color; }
        public int cloud() { return color; }
    }
    private static final Def[] DEFINITIONS = {
        new Def("Hardsok Clouds", "cloud1", 0x8AADB1),
        new Def("Green Hardsok", "cloud2", 0x026A07),
        new Def("Teal Storm", "starfield03", 0x032F2B),
        new Def("Akame Night", "starfield", 0x150C16),
        new Def("Klyrax Violet", "starfield03_1", 0x1E1031),
        new Def("Blue Moon", "starfield_1", 0x12304A)
    };
    public static String[] names() {
        String[] out = new String[DEFINITIONS.length];
        for (int i=0;i<out.length;i++) out[i]=DEFINITIONS[i].name;
        return out;
    }
    public static Def current(int index) {return DEFINITIONS[Math.floorMod(index, DEFINITIONS.length)];}
}
