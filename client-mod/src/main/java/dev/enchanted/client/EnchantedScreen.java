package dev.enchanted.client;

/**
 * Compatibility alias for old repositories that still contain EnchantedScreen.
 * All active GUI functionality lives in LT6Screen.
 */
@Deprecated
public final class EnchantedScreen extends LT6Screen {
    public EnchantedScreen() {
        super();
    }
}
