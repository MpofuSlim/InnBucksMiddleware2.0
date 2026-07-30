package zw.co.innbucks.middleware.auth.pin;

import java.util.regex.Pattern;

public final class PinFormat {

    public static final Pattern VALID = Pattern.compile("^[0-9]{4,6}$");

    private PinFormat() {
    }

    public static boolean isValid(String pin) {
        return pin != null && VALID.matcher(pin).matches();
    }
}
