package io.github.a14398138.zipbackup;
final class NetworkPolicy {
    static boolean allows(boolean mobileAllowed, boolean validated, boolean wifiOrEthernet, boolean unmetered) {
        return validated && (mobileAllowed || (wifiOrEthernet && unmetered));
    }
}
