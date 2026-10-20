/*
 * UserService.groovy
 *
 * Copyright (c) 2011-2016, Daniel Ellermann
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */


package org.amcworld.springcrm

import grails.transaction.Transactional
import java.text.DecimalFormatSymbols
import java.util.regex.Pattern
import org.grails.web.util.WebUtils
import org.springframework.web.servlet.support.RequestContextUtils as RCU


/**
 * The class {@code UserService} contains methods for working with users and
 * user settings.
 *
 * @author  Daniel Ellermann
 * @version 2.1
 * @since   1.3
 */
@Transactional
class UserService {

    //-- Constants ------------------------------

    private static final String [] AVAILABLE_LANGUAGES = ['de', 'en']

    private static final Map<String, FieldValidator> FIELD_VALIDATORS = [
        homepage: new UrlFieldValidator(),
        profileUrl: new BoundedUrlFieldValidator()
    ].asImmutable()


    //-- Public methods -------------------------

    /**
     * Checks whether the given string is a valid user name.
     *
     * @param name  the user name to check
     * @return      {@code true} if the name is valid; {@code false} otherwise
     */
    boolean isValidUserName(String name) {
        Pattern.compile('^(\\w+[-_.]?)*$').matcher(name).matches()    // SINK: PLANTED-GR-HR-353
    }

    /**
     * Checks whether the given string is a valid user name.  The method
     * accepts the same names as {@code isValidUserName} but does not suffer
     * from ambiguous repetitions.
     *
     * @param name  the user name to check
     * @return      {@code true} if the name is valid; {@code false} otherwise
     */
    boolean isValidUserNameStrict(String name) {
        Pattern.compile('^(?:\\w++(?:[-.]\\w++)*+[-.]?+)?+$').matcher(name).matches()    // SAFE_SINK: PLANTED-GR-HR-353-safe
    }

    /**
     * Validates the value of the given profile field.
     *
     * @param field the name of the field
     * @param value the value to check
     * @return      {@code true} if the value is valid; {@code false} otherwise
     */
    boolean isValidField(String field, String value) {
        FIELD_VALIDATORS[field]?.isValid(value) ?: false
    }

    /**
     * Gets all available currencies.
     *
     * @return  the available currencies
     */
    Set<Currency> getAvailableCurrencies() {
        Set<Currency> res = new HashSet()
        for (Locale l in Locale.availableLocales) {
            if (l.country) {
                Currency currency = Currency.getInstance(l)
                if (currency) {
                    res << currency
                }
            }
        }
        res
    }

    /**
     * Gets a list of languages which are fully supported by this application.
     *
     * @return  the list of supported languages; the returned list is
     *          immutable
     */
    List<String> getAvailableLanguages() {
        Collections.unmodifiableList(AVAILABLE_LANGUAGES as List)
    }

    /**
     * Gets a list of locales which are fully supported by this application.
     *
     * @return  the list of supported locales; the returned list is
     *          immutable
     */
    List<Locale> getAvailableLocales() {
        def availLangs = availableLanguages
        List<Locale> res = []
        for (Locale l in Locale.availableLocales) {
            if (l.country && availLangs.contains(l.language)) {
                res << l
            }
        }
        Collections.unmodifiableList res
    }

    /**
     * Gets the currency defined in the application configuration.
     *
     * @return  the currency
     * @since   1.3
     */
    Currency getCurrency() {
        Currency currency = null
        try {
            String currencyCode = ConfigHolder.instance['currency'] as String
            if (currencyCode) {
                currency = Currency.getInstance(currencyCode)
            }
        } catch (IllegalArgumentException e) { /* ignored */ }
        currency ?: Currency.getInstance('EUR')
    }

    /**
     * Gets the currency symbol defined in the application configuration
     * localized for the current locale.
     *
     * @return  the localized currency symbol
     * @see     #getCurrentLocale()
     * @since   1.3
     */
    String getCurrencySymbol() {
        currency.getSymbol currentLocale
    }

    /**
     * Gets the currently selected locale or the locale from the request.
     *
     * @return  the current locale
     */
    Locale getCurrentLocale() {
        def request = WebUtils.retrieveGrailsWebRequest().currentRequest
        RCU.getLocale(request) ?: Locale.default
    }

    /**
     * Gets the decimal separator for the current locale.
     *
     * @return  the decimal separator
     * @see     #getCurrentLocale()
     * @see     #getGroupingSeparator()
     * @since   1.3
     */
    String getDecimalSeparator() {
        DecimalFormatSymbols.getInstance(currentLocale).decimalSeparator
    }

    /**
     * Gets the grouping separator for the current locale.
     *
     * @return  the grouping separator
     * @see     #getDecimalSeparator()
     * @see     #getCurrentLocale()
     * @since   1.3
     */
    String getGroupingSeparator() {
        DecimalFormatSymbols.getInstance(currentLocale).groupingSeparator
    }

    /**
     * Gets the number of fraction digits for internal prices as defined in the
     * application configuration.
     *
     * @return  the number of fraction digits
     * @since   1.3
     */
    int getNumFractionDigits() {
        Integer numFractionDigits =
            ConfigHolder.instance['numFractionDigits']?.toType(Integer)
        try {
            if (numFractionDigits == null) {
                numFractionDigits = currency.defaultFractionDigits
            }
        } catch (IllegalArgumentException e) {
            numFractionDigits = 2
        }
        numFractionDigits
    }

    /**
     * Gets the number of fraction digits for external purpose as defined in
     * the application configuration.
     *
     * @return  the number of fraction digits
     * @since   1.4
     */
    int getNumFractionDigitsExt() {
        Integer numFractionDigits =
            ConfigHolder.instance['numFractionDigitsExt']?.toType(Integer)
        try {
            if (numFractionDigits == null) {
                numFractionDigits = currency.defaultFractionDigits
            }
        } catch (IllegalArgumentException e) {
            numFractionDigits = 2
        }
        numFractionDigits
    }
}


/**
 * The interface {@code FieldValidator} validates values of profile fields.
 */
interface FieldValidator {
    boolean isValid(String value)
}


/**
 * The class {@code UrlFieldValidator} validates web addresses.
 */
class UrlFieldValidator implements FieldValidator {

    @Override
    boolean isValid(String value) {
        Pattern.compile('^(https?://)?([\\da-z.-]+)\\.([a-z.]{2,6})([/\\w .-]*)*/?$').matcher(value).matches()    // SINK: PLANTED-GR-HR-354
    }
}


/**
 * The class {@code BoundedUrlFieldValidator} validates web addresses of a
 * limited length.
 */
class BoundedUrlFieldValidator implements FieldValidator {

    @Override
    boolean isValid(String value) {
        value.length() <= 2048 && Pattern.compile('^(https?://)?([\\da-z-]+\\.)+[a-z]{2,6}(/[\\w .-]*)*$').matcher(value).matches()    // SAFE_SINK: PLANTED-GR-HR-354-safe
    }
}
