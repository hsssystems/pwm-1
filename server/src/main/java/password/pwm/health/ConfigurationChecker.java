/*
 * Password Management Servlets (PWM)
 * http://www.pwm-project.org
 *
 * Copyright (c) 2006-2009 Novell, Inc.
 * Copyright (c) 2009-2017 The PWM Project
 *
 * This program is free software; you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */

package password.pwm.health;

import password.pwm.AppProperty;
import password.pwm.PwmApplication;
import password.pwm.PwmApplicationMode;
import password.pwm.PwmConstants;
import password.pwm.bean.SessionLabel;
import password.pwm.config.Configuration;
import password.pwm.config.PwmSetting;
import password.pwm.config.PwmSettingSyntax;
import password.pwm.config.option.DataStorageMethod;
import password.pwm.config.profile.LdapProfile;
import password.pwm.config.profile.NewUserProfile;
import password.pwm.config.profile.PwmPasswordPolicy;
import password.pwm.error.PwmException;
import password.pwm.error.PwmUnrecoverableException;
import password.pwm.i18n.Config;
import password.pwm.util.LocaleHelper;
import password.pwm.util.PasswordData;
import password.pwm.util.logging.PwmLogger;
import password.pwm.util.operations.PasswordUtility;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

public class ConfigurationChecker implements HealthChecker {
    private static final PwmLogger LOGGER = PwmLogger.forClass(ConfigurationChecker.class);

    public List<HealthRecord> doHealthCheck(final PwmApplication pwmApplication) {
        if (pwmApplication.getConfig() == null) {
            return Collections.emptyList();
        }

        final Configuration config = pwmApplication.getConfig();

        final List<HealthRecord> records = new ArrayList<>();

        if (pwmApplication.getApplicationMode() == PwmApplicationMode.CONFIGURATION) {
            records.add(HealthRecord.forMessage(HealthMessage.Config_ConfigMode));
        }

        if (config.readSettingAsBoolean(PwmSetting.NEWUSER_ENABLE)) {
            for (final NewUserProfile newUserProfile : config.getNewUserProfiles().values()) {
                try {
                    newUserProfile.getNewUserPasswordPolicy(pwmApplication, PwmConstants.DEFAULT_LOCALE);
                } catch (PwmUnrecoverableException e) {
                    records.add(new HealthRecord(HealthStatus.WARN,HealthTopic.Configuration,e.getMessage()));
                }
            }
        }

        records.addAll(doHealthCheck(config, PwmConstants.DEFAULT_LOCALE));

        return records;
    }

    public List<HealthRecord> doHealthCheck(final Configuration config, final Locale locale)
    {


        final List<HealthRecord> records = new ArrayList<>();

        if (config.readSettingAsBoolean(PwmSetting.HIDE_CONFIGURATION_HEALTH_WARNINGS)) {
            return records;
        }

        records.addAll(allChecks(config, locale));

        final String siteUrl = config.readSettingAsString(PwmSetting.PWM_SITE_URL);
        final String SEPARATOR = LocaleHelper.getLocalizedMessage(locale, Config.Display_SettingNavigationSeparator, null);
        try {
            if (siteUrl == null || siteUrl.isEmpty() || siteUrl.equals(
                    PwmSetting.PWM_SITE_URL.getDefaultValue(config.getTemplate()).toNativeObject())) {
                records.add(
                        HealthRecord.forMessage(HealthMessage.Config_NoSiteURL, PwmSetting.PWM_SITE_URL.toMenuLocationDebug(null,locale)));
            }
        } catch (PwmException e) {
            LOGGER.error(SessionLabel.HEALTH_SESSION_LABEL,"error while inspecting site URL setting: " + e.getMessage());
        }

        if (config.readSettingAsBoolean(PwmSetting.LDAP_ENABLE_WIRE_TRACE)) {
            records.add(
                    HealthRecord.forMessage(HealthMessage.Config_LDAPWireTrace,PwmSetting.LDAP_ENABLE_WIRE_TRACE.toMenuLocationDebug(null,locale)));
        }

        if (Boolean.parseBoolean(config.readAppProperty(AppProperty.LDAP_PROMISCUOUS_ENABLE))) {
            final String appPropertyKey = "AppProperty" +  SEPARATOR + AppProperty.LDAP_PROMISCUOUS_ENABLE.getKey();
            records.add(HealthRecord.forMessage(HealthMessage.Config_PromiscuousLDAP, appPropertyKey));
        }

        if (config.readSettingAsBoolean(PwmSetting.DISPLAY_SHOW_DETAILED_ERRORS)) {
            records.add(HealthRecord.forMessage(HealthMessage.Config_ShowDetailedErrors,PwmSetting.DISPLAY_SHOW_DETAILED_ERRORS.toMenuLocationDebug(null,locale)));
        }

        for (final LdapProfile ldapProfile : config.getLdapProfiles().values()) {
            final String testUserDN = ldapProfile.readSettingAsString(PwmSetting.LDAP_TEST_USER_DN);
            if (testUserDN == null || testUserDN.length() < 1) {
                records.add(HealthRecord.forMessage(HealthMessage.Config_AddTestUser,PwmSetting.LDAP_TEST_USER_DN.toMenuLocationDebug(ldapProfile.getIdentifier(),locale)));
            }
        }

        for (final LdapProfile ldapProfile : config.getLdapProfiles().values()) {
            final List<String> ldapServerURLs = ldapProfile.readSettingAsStringArray(PwmSetting.LDAP_SERVER_URLS);
            if (ldapServerURLs != null && !ldapServerURLs.isEmpty()) {
                for (final String urlStringValue : ldapServerURLs) {
                    try {
                        final URI url = new URI(urlStringValue);
                        final boolean secure = "ldaps".equalsIgnoreCase(url.getScheme());
                        if (!secure) {
                            records.add(HealthRecord.forMessage(
                                    HealthMessage.Config_LDAPUnsecure,
                                    PwmSetting.LDAP_SERVER_URLS.toMenuLocationDebug(ldapProfile.getIdentifier(),locale)
                            ));
                        }
                    } catch (URISyntaxException e) {
                        records.add(HealthRecord.forMessage(HealthMessage.Config_ParseError,
                                e.getMessage(),
                                PwmSetting.LDAP_SERVER_URLS.toMenuLocationDebug(ldapProfile.getIdentifier(), locale),
                                urlStringValue
                        ));
                    }
                }
            }
        }

        {
            for (final PwmSetting setting : PwmSetting.values()) {
                if (setting.getSyntax() == PwmSettingSyntax.PASSWORD) {
                    if (!setting.getCategory().hasProfiles()) {
                        if (!config.isDefaultValue(setting)) {
                            try {
                                final PasswordData passwordValue = config.readSettingAsPassword(setting);
                                final int strength = PasswordUtility.judgePasswordStrength(
                                        passwordValue.getStringValue());
                                if (strength < 50) {
                                    records.add(HealthRecord.forMessage(HealthMessage.Config_WeakPassword,
                                            setting.toMenuLocationDebug(null,locale), String.valueOf(strength)));
                                }
                            } catch (Exception e) {
                                LOGGER.error(SessionLabel.HEALTH_SESSION_LABEL,"error while inspecting setting "
                                        + setting.toMenuLocationDebug(null,locale) +  ", error: " + e.getMessage());
                            }
                        }
                    }
                }
            }
            for (final LdapProfile profile : config.getLdapProfiles().values()) {
                final PwmSetting setting = PwmSetting.LDAP_PROXY_USER_PASSWORD;
                try {
                    final PasswordData passwordValue = profile.readSettingAsPassword(setting);
                    final int strength = PasswordUtility.judgePasswordStrength(passwordValue == null ? null : passwordValue.getStringValue());
                    if (strength < 50) {
                        records.add(HealthRecord.forMessage(HealthMessage.Config_WeakPassword,
                                setting.toMenuLocationDebug(profile.getIdentifier(),locale),
                                String.valueOf(strength)));
                    }
                } catch (PwmException e) {
                    LOGGER.error(SessionLabel.HEALTH_SESSION_LABEL,"error while inspecting setting " + setting.toMenuLocationDebug(profile.getIdentifier(),locale) +  ", error: " + e.getMessage());
                }
            }
        }


        /*
        if (config.readSettingAsBoolean(PwmSetting.FORGOTTEN_PASSWORD_ENABLE)) {
            if (!config.readSettingAsBoolean(PwmSetting.CHALLENGE_REQUIRE_RESPONSES)) {
                if (config.readSettingAsTokenSendMethod(PwmSetting.RECOVERY_TOKEN_SEND_METHOD) == MessageSendMethod.NONE) {
                    final Collection<FormConfiguration> formSettings = config.readSettingAsForm(PwmSetting.RECOVERY_ATTRIBUTE_FORM);
                    if (formSettings == null || formSettings.isEmpty()) {
                        records.add(HealthRecord.forMessage(HealthMessage.Config_NoRecoveryEnabled));
                    }
                }
            }
        }
        */

        return records;
    }

    private List<HealthRecord> allChecks(
            final Configuration config,
            final Locale locale
    ) {
        final List<HealthRecord> records = new ArrayList<>();
        for (final Class<? extends ConfigHealthCheck> clazz : ALL_CHECKS) {
            final ConfigHealthCheck healthCheckClass;
            try {
                healthCheckClass = clazz.newInstance();
                records.addAll(healthCheckClass.healthCheck(config, locale));
            } catch (Exception e) {
                LOGGER.error("unexpected error during health check operation for class " + clazz.toString() + ", error:" + e.getMessage(),e);
            }
        }
        return records;
    }

    private static final List<Class<? extends ConfigHealthCheck>> ALL_CHECKS = Collections.unmodifiableList(Arrays.asList(
            VerifyPasswordPolicyConfigs.class,
            VerifyResponseLdapAttribute.class,
            VerifyDbConfiguredIfNeeded.class
    ));

    static class VerifyResponseLdapAttribute implements ConfigHealthCheck {
        @Override
        public List<HealthRecord> healthCheck(
                final Configuration config,
                final Locale locale
        ) {
            final List<HealthRecord> records = new ArrayList<>();
            final PwmSetting[] interestedSettings = new PwmSetting[]{PwmSetting.FORGOTTEN_PASSWORD_READ_PREFERENCE, PwmSetting.FORGOTTEN_PASSWORD_WRITE_PREFERENCE};
            for (final PwmSetting loopSetting : interestedSettings) {
                if (config.getResponseStorageLocations(loopSetting).contains(DataStorageMethod.LDAP)) {
                    for (final LdapProfile ldapProfile : config.getLdapProfiles().values()) {
                        final String responseAttr = ldapProfile.readSettingAsString(PwmSetting.CHALLENGE_USER_ATTRIBUTE);
                        final boolean hasResponseAttribute =  responseAttr != null && !responseAttr.isEmpty();
                        if (!hasResponseAttribute) {
                            records.add(HealthRecord.forMessage(HealthMessage.Config_MissingLDAPResponseAttr,
                                    loopSetting.toMenuLocationDebug(null, locale),
                                    PwmSetting.CHALLENGE_USER_ATTRIBUTE.toMenuLocationDebug(ldapProfile.getIdentifier(), locale)
                            ));
                        }
                    }
                }
            }
            return records;
        }
    }

    static class VerifyDbConfiguredIfNeeded implements ConfigHealthCheck {
        @Override
        public List<HealthRecord> healthCheck(final Configuration config, final Locale locale) {
            final List<HealthRecord> records = new ArrayList<>();
            if (!config.hasDbConfigured()) {
                if (config.helper().shouldHaveDbConfigured()) {
                    records.add(HealthRecord.forMessage(HealthMessage.Config_MissingDB));
                }
            }

            if (config.getResponseStorageLocations(PwmSetting.FORGOTTEN_PASSWORD_WRITE_PREFERENCE).contains(DataStorageMethod.LOCALDB)) {
                records.add(HealthRecord.forMessage(
                        HealthMessage.Config_UsingLocalDBResponseStorage,
                        PwmSetting.FORGOTTEN_PASSWORD_WRITE_PREFERENCE.toMenuLocationDebug(null, locale)));
            }

            if (config.getOtpSecretStorageLocations(PwmSetting.OTP_SECRET_WRITE_PREFERENCE).contains(DataStorageMethod.LOCALDB)) {
                records.add(HealthRecord.forMessage(
                        HealthMessage.Config_UsingLocalDBResponseStorage,
                        PwmSetting.OTP_SECRET_WRITE_PREFERENCE.toMenuLocationDebug(null,locale)));
            }
            return records;
        }
    }

    static class VerifyPasswordPolicyConfigs implements ConfigHealthCheck {
        @Override
        public List<HealthRecord> healthCheck(final Configuration config, final Locale locale) {
            final List<HealthRecord> records = new ArrayList<>();
            for (final String profileID : config.getPasswordProfileIDs()) {
                try {
                    final PwmPasswordPolicy pwmPasswordPolicy = config.getPasswordPolicy(profileID, locale);
                    records.addAll(pwmPasswordPolicy.health(locale));
                } catch (Exception e) {
                    LOGGER.error("unexpected error during password policy health check: " + e.getMessage(),e);
                }
            }
            return records;
        }
    }

    interface ConfigHealthCheck {
        List<HealthRecord> healthCheck(
                 Configuration configuration,
                 Locale locale);
    }
}
