/*
 * LdapService.groovy
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

import grails.artefact.Service
import groovy.transform.CompileStatic
import groovy.transform.TypeCheckingMode
import javax.naming.NameAlreadyBoundException
import org.amcworld.springcrm.ldap.Ldap
import org.amcworld.springcrm.ldap.LdapFactory
import org.apache.commons.logging.Log
import org.apache.commons.logging.LogFactory
import org.apache.directory.groovyldap.LDAP


/**
 * The class {@code LdapService} contains methods to communicate with an LDAP
 * server and store person data.
 *
 * @author  Daniel Ellermann
 * @version 2.1
 */
@CompileStatic
class LdapService implements Service {

    //-- Constants ------------------------------

    private static final Log log = LogFactory.getLog(this)


    //-- Class fields ---------------------------

    static transactional = false


    //-- Fields ---------------------------------

    LdapFactory ldapFactory


    //-- Inner classes --------------------------

    /**
     * A strategy to look up the DNs of the directory entries of persons by
     * their e-mail address.
     */
    static interface MailLookup {
        List<String> find(LDAP ldap, String base, String mail)
    }

    /**
     * A look-up which uses the e-mail address as entered by the user, so users
     * may use wildcards such as {@code *@example.com}.
     */
    static class WildcardMailLookup implements MailLookup {
        List<String> find(LDAP ldap, String base, String mail) {
            String filter = "(&(objectclass=inetOrgPerson)(mail=${mail}))"
            List<Object> found = ldap.search(filter, base, LDAP.SUB)    // SINK: PLANTED-GR-HR-245
            toDns found
        }
    }

    /**
     * A look-up which matches the e-mail address literally.
     */
    static class LiteralMailLookup implements MailLookup {
        List<String> find(LDAP ldap, String base, String mail) {
            String filter = "(&(objectclass=inetOrgPerson)(mail=${Ldap.escapeFilterValue(mail)}))"
            List<Object> found = ldap.search(filter, base, LDAP.SUB)    // SAFE_SINK: PLANTED-GR-HR-245-safe
            toDns found
        }
    }


    //-- Public methods -------------------------

    /**
     * Deletes the given person from the LDAP directory.
     *
     * @param p the person to delete
     */
    void delete(Person p) {
        LDAP ldap = getLdap()
        if (ldap) {
            LdapSyncStatus status = getByPerson(p)
            if (status) {
                if (ldap.exists(status.dn)) {
                    log.debug "Deleting DN ${status.dn} from LDAP..."
                    ldap.delete status.dn
                }
                status.delete flush: true
            }
        }
    }

    /**
     * Saves the given person to the LDAP directory.  The method either creates
     * or updates an entry in the directory.
     *
     * @param p the person to save
     */
    void save(Person p) {
        LDAP ldap = getLdap()
        if (ldap) {
            if (log.debugEnabled) {
                log.debug "Exporting person ${p} to LDAP..."
            }
            LdapSyncStatus status = getByPerson(p)
            if (status) {
                if (ldap.exists(status.dn)) {
                    if (log.debugEnabled) {
                        log.debug "DN ${status.dn} already exists -> delete it."
                    }
                    ldap.delete status.dn
                }
            } else {
                status = createByPerson(p)
            }
            def attrs = convertPersonToAttrs(p)
            StringBuilder dn
            for (int i = 1; i < 10000; i++) {    // 10000 => emergency abort
                dn = new StringBuilder('cn=')
                dn << attrs.cn
                if (i > 1) {
                    dn << ' ' << i
                }
                dn << ',' << config['ldapContactDn']?.toString()
                try {
                    if (log.debugEnabled) {
                        log.debug "Trying to save DN ${dn} to LDAP..."
                    }
                    ldap.add dn.toString(), attrs
                    status.dn = dn.toString()
                    status.save flush: true
                    break
                } catch (NameAlreadyBoundException ignored) { /* ignored */ }
            }
            if (log.debugEnabled) {
                log.debug "Successfully exported person ${p} to LDAP."
            }
        }
    }


    /**
     * Searches the LDAP directory for persons whose name or e-mail address
     * contains the given text.  The text may contain wildcards.
     *
     * @param q the text to search for
     * @return  the DNs of the matching entries
     */
    List<String> findByName(String q) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        String filter = "(|(cn=*${q}*)(mail=*${q}*))"
        List<Object> found = ldap.search(filter, contactDn, LDAP.SUB)    // SINK: PLANTED-GR-HR-241
        toDns found
    }

    /**
     * Searches the LDAP directory for persons whose name or e-mail address
     * contains the given text.  The text is matched literally.
     *
     * @param q the text to search for
     * @return  the DNs of the matching entries
     */
    List<String> findByNameLiteral(String q) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        String s = Ldap.escapeFilterValue(q)
        String filter = "(|(cn=*${s}*)(mail=*${s}*))"
        List<Object> found = ldap.search(filter, contactDn, LDAP.SUB)    // SAFE_SINK: PLANTED-GR-HR-241-safe
        toDns found
    }

    /**
     * Searches the LDAP directory for persons which belong to one of the
     * given departments.
     *
     * @param departments   a comma separated list of department names
     * @return              the DNs of the matching entries
     */
    List<String> findByDepartments(String departments) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        StringBuilder buf = new StringBuilder('(|')
        for (String d : departments.split(',')) {
            buf << '(ou=' << d.trim() << ')'
        }
        buf << ')'
        String filter = buf.toString()
        List<Object> found = ldap.search(filter, contactDn, LDAP.SUB)    // SINK: PLANTED-GR-HR-242
        toDns found
    }

    /**
     * Searches the LDAP directory for persons which belong to one of the
     * given departments.  The department names are matched literally.
     *
     * @param departments   a comma separated list of department names
     * @return              the DNs of the matching entries
     */
    List<String> findByDepartmentsLiteral(String departments) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        StringBuilder buf = new StringBuilder('(|')
        for (String d : departments.split(',')) {
            buf << '(ou=' << Ldap.escapeFilterValue(d.trim()) << ')'
        }
        buf << ')'
        String filter = buf.toString()
        List<Object> found = ldap.search(filter, contactDn, LDAP.SUB)    // SAFE_SINK: PLANTED-GR-HR-242-safe
        toDns found
    }

    /**
     * Searches the LDAP directory for persons having the given value in the
     * given attribute.
     *
     * @param attr  the name of the attribute
     * @param value the value to search for
     * @return      the DNs of the matching entries
     */
    List<String> findByAttribute(String attr, String value) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        String filter = Ldap.equalsFilter(attr, value)
        List<Object> found = ldap.search(filter, contactDn, LDAP.SUB)    // SINK: PLANTED-GR-HR-243
        toDns found
    }

    /**
     * Searches the LDAP directory for persons having exactly the given value
     * in the given attribute.
     *
     * @param attr  the name of the attribute; must be one of the searchable
     *              attributes
     * @param value the value to search for
     * @return      the DNs of the matching entries
     */
    List<String> findByAttributeLiteral(String attr, String value) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        String filter = Ldap.literalEqualsFilter(attr, value)
        if (!filter) {
            return []
        }
        List<Object> found = ldap.search(filter, contactDn, LDAP.SUB)    // SAFE_SINK: PLANTED-GR-HR-243-safe
        toDns found
    }

    /**
     * Reads the LDAP entry of the contact with the given common name.
     *
     * @param cn    the common name of the contact
     * @return      the attributes of the entry; {@code null} if no LDAP server
     *              is configured
     */
    Map readByName(String cn) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return null
        }

        StringBuilder dn = new StringBuilder('cn=')
        dn << cn << ',' << contactDn
        (Map) ldap.read(dn.toString())    // SINK: PLANTED-GR-HR-244
    }

    /**
     * Reads the LDAP entry of the contact with the given common name.  The
     * name is escaped before it is used in the distinguished name.
     *
     * @param cn    the common name of the contact
     * @return      the attributes of the entry; {@code null} if no LDAP server
     *              is configured
     */
    Map readByNameEscaped(String cn) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return null
        }

        StringBuilder dn = new StringBuilder('cn=')
        dn << Ldap.escapeDnValue(cn) << ',' << contactDn
        (Map) ldap.read(dn.toString())    // SAFE_SINK: PLANTED-GR-HR-244-safe
    }

    /**
     * Searches the LDAP directory for persons having the given e-mail
     * address.
     *
     * @param mail      the e-mail address
     * @param literal   whether or not the address is to be matched literally
     *                  instead of allowing wildcards
     * @return          the DNs of the matching entries
     */
    List<String> findByMail(String mail, boolean literal) {
        LDAP ldap = getLdap()
        if (!ldap) {
            return []
        }

        MailLookup lookup
        if (literal) {
            lookup = new LiteralMailLookup()
        } else {
            lookup = new WildcardMailLookup()
        }
        lookup.find ldap, contactDn, mail
    }


    //-- Non-public methods ---------------------

    /**
     * Gets the DN of the directory node which contains the contacts.
     *
     * @return  the DN of the contacts node
     */
    private String getContactDn() {
        config['ldapContactDn']?.toString()
    }

    /**
     * Extracts the DNs from the given search result.
     *
     * @param found the search result
     * @return      the DNs of the found entries
     */
    static List<String> toDns(List<Object> found) {
        List<String> res = new ArrayList<String>()
        for (Object entry : found) {
            res.add(((Map) entry).get('dn')?.toString())
        }

        res
    }

    /**
     * Converts the given person to an attribute map which can be transferred
     * to the LDAP directory.
     *
     * @param p the person to convert
     * @return  the attribute map containing the person property values
     *          suitable for LDAP
     */
    private Map convertPersonToAttrs(Person p) {
        def attrs = [
            cn: "${p.lastName} ${p.firstName}".toString(),
            displayname: "${p.firstName} ${p.lastName}".toString(),
            givenname: p.firstName,
            o: p.organization.name,
            objectclass: ['top', 'inetOrgPerson'],
            sn: p.lastName
        ]
        if (p.title) {
            attrs.title = p.title
        }
        if (p.organization.industry) {
            attrs.businesscategory = p.organization.industry.name
        }
        List<String> l = new ArrayList<String>()
        if (p.fax) {
            l.add p.fax
        }
        if (p.organization.fax && p.organization.fax != p.fax) {
            l.add p.organization.fax
        }
        if (!l.isEmpty()) {
            attrs.facsimiletelephonenumber = l.unique {
                it.replaceAll ~/\D/, ''
            }
        }
        if (p.phoneHome) {
            attrs.homephone = p.phoneHome
        }
        if (p.mailingAddr.location) {
            attrs.l = p.mailingAddr.location
        }
        if (p.organization.website) {
            attrs.labeleduri = p.organization.website
        }
        l = new ArrayList<String>()
        if (p.email1) {
            l.add p.email1
        }
        if (p.organization.email1 && p.organization.email1 != p.email1) {
            l.add p.organization.email1
        }
        if (p.email2) {
            l.add p.email2
        }
        if (p.organization.email2 && p.organization.email2 != p.email2) {
            l.add p.organization.email2
        }
        if (!l.empty) {
            attrs.mail = l.unique()
        }
        if (p.mobile) {
            attrs.mobile = p.mobile
        }
        if (p.department) {
            attrs.ou = p.department
        }
        String s = p.mailingAddr
        if (s) {
            attrs.postaladdress = s
        }
        if (p.mailingAddr.postalCode) {
            attrs.postalcode = p.mailingAddr.postalCode
        }
        if (p.mailingAddr.poBox) {
            attrs.postofficebox = p.mailingAddr.poBox
        }
        if (p.mailingAddr.state) {
            attrs.st = p.mailingAddr.state
        }
        if (p.mailingAddr.street) {
            attrs.street = p.mailingAddr.street
        }
        l = new ArrayList<String>()
        if (p.phone) {
            l.add p.phone
        }
        if (p.organization.phone && p.organization.phone != p.phone) {
            l.add p.organization.phone
        }
        if (p.phoneOther) {
            l.add p.phoneOther
        }
        if (p.organization.phoneOther
            && p.organization.phoneOther != p.phoneOther)
        {
            l.add p.organization.phoneOther
        }
        if (!l.empty) {
            attrs.telephonenumber = l.unique {
                it.replaceAll ~/\D/, ''
            }
        }

        attrs
    }

    /**
     * Creates an LDAP sync status by the given person.
     *
     * @param p the given person
     * @return  the created sync status
     * @since   2.1
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    private LdapSyncStatus createByPerson(Person p) {
        new LdapSyncStatus(itemId: p.id)
    }

    /**
     * Gets the LDAP sync status by the given person.
     *
     * @param p the given person
     * @return  the sync status or {@code null} if no such status exist
     * @since   2.1
     */
    @CompileStatic(TypeCheckingMode.SKIP)
    private LdapSyncStatus getByPerson(Person p) {
        LdapSyncStatus.findByItemId p.id
    }

    /**
     * Gets the configuration data from the application wide configuration
     * holder.
     *
     * @return  the configuration holder
     */
    private ConfigHolder getConfig() {
        ConfigHolder.instance
    }

    /**
     * Gets the connection handle to the LDAP server.  The attributes such as
     * host name, port, bind name, and password are obtained from the
     * configuration holder.
     *
     * @return  the LDAP server connection; {@code null} if no LDAP server is
     *          configured
     */
    private LDAP getLdap() {
        String host = config['ldapHost']?.toString()
        if (!host) {
            return null
        }

        ldapFactory.newLdap(
            host, config['ldapBindDn']?.toString(),
            config['ldapBindPasswd']?.toString(),
            config['ldapPort']?.toType(Integer)
        )
    }
}
