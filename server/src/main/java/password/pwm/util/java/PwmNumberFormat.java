package password.pwm.util.java;

import password.pwm.PwmConstants;

import java.text.NumberFormat;
import java.util.Locale;

public class PwmNumberFormat {
    private final Locale locale;

    private PwmNumberFormat(final Locale locale) {
        this.locale = locale;
    }

    public static PwmNumberFormat forLocale(final Locale locale) {
        return new PwmNumberFormat(locale);
    }

    public static PwmNumberFormat forDefaultLocale() {
        return new PwmNumberFormat(PwmConstants.DEFAULT_LOCALE);
    }

    public String format(final long number) {
        final NumberFormat numberFormat = NumberFormat.getInstance(locale);
        return numberFormat.format(number);
    }
}
