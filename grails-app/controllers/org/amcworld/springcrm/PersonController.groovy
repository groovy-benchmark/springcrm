/*
 * PersonController.groovy
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

import javax.naming.AuthenticationException
import javax.naming.CommunicationException
import javax.naming.NameNotFoundException
import javax.servlet.http.HttpServletResponse
import java.util.regex.Pattern
import net.sf.jmimemagic.Magic
import org.amcworld.springcrm.google.GoogleContactSync
import org.springframework.dao.DataIntegrityViolationException


/**
 * The class {@code PersonController} contains actions which manage persons
 * that belong to an organization.
 *
 * @author  Daniel Ellermann
 * @version 2.1
 */
class PersonController {

    //-- Class fields ---------------------------

    static allowedMethods = [save: 'POST', update: 'POST', delete: 'GET']


    //-- Fields ---------------------------------

    GoogleContactSync googleContactSync
    LdapService ldapService


    //-- Public methods -------------------------

    def index() {
        int max = params.max =
            Math.min(params.max ? params.int('max') : 10, 100)
        if (params.letter) {
            int num = Person.countByLastNameLessThan(params.letter.toString())
            params.sort = 'lastName'
            params.offset = Math.floor(num / max) * max
        }

        [
            personInstanceList: Person.list(params),
            personInstanceTotal: Person.count()
        ]
    }

    def listEmbedded(Long organization) {
        List<Person> l = null
        int count = 0
        Map<String, Object> linkParams = null

        if (organization) {
            def organizationInstance = Organization.get(organization)
            if (organizationInstance) {
                l = Person.findAllByOrganization(organizationInstance, params)
                count = Person.countByOrganization(organizationInstance)
                linkParams = [organization: organizationInstance.id]
            }
        }
        params.max = Math.min(params.max ? params.int('max') : 10, 100)

        [
            personInstanceList: l, personInstanceTotal: count,
            linkParams: linkParams
        ]
    }

    def create() {
        [personInstance: new Person(params)]
    }

    def copy(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            flash.message = message(
                code: 'default.not.found.message',
                args: [message(code: 'person.label'), id]
            )
            redirect action: 'index'
            return
        }

        personInstance = new Person(personInstance)
        render view: 'create', model: [personInstance: personInstance]
    }

    def save() {
        def personInstance = new Person(params)
        if (!params.picture?.size) {
            personInstance.picture = null
        }
        if (!personInstance.save(flush: true)) {
            render view: 'create', model: [personInstance: personInstance]
            return
        }

        request.personInstance = personInstance
        if (ldapService && !isExcludeFromSync(personInstance)) {
            ldapService.save personInstance
        }

        flash.message = message(
            code: 'default.created.message',
            args: [message(code: 'person.label'), personInstance.toString()]
        )

        redirect action: 'show', id: personInstance.id
    }

    def show(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            flash.message = message(
                code: 'default.not.found.message',
                args: [message(code: 'person.label'), id]
            )
            redirect action: 'index'
            return
        }

        [personInstance: personInstance]
    }

    def edit(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            flash.message = message(
                code: 'default.not.found.message',
                args: [message(code: 'person.label'), id]
            )
            redirect action: 'index'
            return
        }

        [personInstance: personInstance]
    }

    def update(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            flash.message = message(
                code: 'default.not.found.message',
                args: [message(code: 'person.label'), id]
            )
            redirect action: 'index'
            return
        }

        if (params.version) {
            def version = params.version.toLong()
            if (personInstance.version > version) {
                personInstance.errors.rejectValue(
                    'version', 'default.optimistic.locking.failure',
                    [message(code: 'person.label')] as Object[],
                    'Another user has updated this Person while you were editing'
                )
                render view: 'edit', model: [personInstance: personInstance]
                return
            }
        }

        if (params.autoNumber) {
            params.number = personInstance.number
        }
        byte [] picture = personInstance.picture
        personInstance.properties = params
        if (params.pictureRemove == '1') {
            personInstance.picture = null
        } else if (params.picture?.isEmpty()) {
            personInstance.picture = picture
        }
        if (!personInstance.save(flush: true)) {
            render view: 'edit', model: [personInstance: personInstance]
            return
        }

        request.personInstance = personInstance
        if (ldapService && !isExcludeFromSync(personInstance)) {
            ldapService.save personInstance
        }

        flash.message = message(
            code: 'default.updated.message',
            args: [message(code: 'person.label'), personInstance.toString()]
        )

        redirect action: 'show', id: personInstance.id
    }

    def delete(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            flash.message = message(
                code: 'default.not.found.message',
                args: [message(code: 'person.label'), id]
            )

            redirect action: 'index'
            return
        }

        request.personInstance = personInstance
        try {
            personInstance.delete flush: true
            if (ldapService) {
                ldapService.delete personInstance
            }

            flash.message = message(
                code: 'default.deleted.message',
                args: [message(code: 'person.label')]
            )

            redirect action: 'index'
        } catch (DataIntegrityViolationException ignored) {
            flash.message = message(
                code: 'default.not.deleted.message',
                args: [message(code: 'person.label')]
            )

            redirect action: 'show', id: id
        }
    }

    def getPicture(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            render status: HttpServletResponse.SC_NOT_FOUND
            return
        }

        response.contentType =
            Magic.getMagicMatch(personInstance.picture).mimeType
        response.contentLength = personInstance.picture.length
        response.outputStream << personInstance.picture

        null
    }

    def getPhoneNumbers(Long id) {
        def personInstance = Person.get(id)
        if (!personInstance) {
            render status: HttpServletResponse.SC_NOT_FOUND
            return
        }

        List<String> phoneNumbers = [
                personInstance.phone,
                personInstance.phoneHome,
                personInstance.mobile,
                personInstance.fax,
                personInstance.phoneAssistant,
                personInstance.phoneOther,
                personInstance.organization.phone,
                personInstance.organization.phoneOther,
                personInstance.organization.fax
            ]
            .findAll({ it != '' })
            .unique()

        [phoneNumbers: phoneNumbers]
    }

    def find(Long organization) {
        def organizationInstance = Organization.get(organization)
        def c = Person.createCriteria()
        def list = c.list {
            eq('organization', organizationInstance)
            and {
                or {
                    ilike('lastName', "${params.name}%")
                    ilike('firstName', "${params.name}%")
                }
            }
            order('lastName', 'asc')
        }

        [personInstanceList: list]
    }

    def gdatasync() {
        if (googleContactSync) {
            googleContactSync.sync(
                ((Credential) session.credential).loadUser()
            )
            flash.message = message(
                code: 'default.gdata.allsync.success',
                args: [message(code: 'person.plural')]
            )
        }

        if (params.returnUrl) {
            redirect url: params.returnUrl
        } else {
            redirect action: 'index'
        }
    }

    def ldapexport(Long id) {
        if (ldapService) {
            List<Long> excludeIds = excludeFromSyncValues
            if (id) {
                def personInstance = Person.get(id)
                if (personInstance
                    && !isExcludeFromSync(personInstance, excludeIds))
                {
                    ldapService.save personInstance
                    flash.message = message(
                        code: 'default.ldap.export.success',
                        args: [
                            message(code: 'person.label'),
                            personInstance.toString()
                        ]
                    )
                } else {
                    flash.message = message(
                        code: 'default.not.found.message',
                        args: [message(code: 'person.label'), id]
                    )
                }
                redirect action: 'show', id: id
                return
            }

            List<Person> personInstanceList = Person.list()
            for (Person personInstance : personInstanceList) {
                if (!isExcludeFromSync(personInstance, excludeIds)) {
                    ldapService.save personInstance
                }
            }
            flash.message = message(
                code: 'default.ldap.allexport.success',
                args: [message(code: 'person.plural')]
            )
        }

        if (params.returnUrl) {
            redirect url: params.returnUrl
        } else {
            redirect action: 'index'
        }
    }

    def ldapdelete(Long id) {
        if (ldapService && id) {
            def personInstance = Person.get(id)
            if (personInstance) {
                ldapService.delete personInstance
            }
        }

        redirect action: 'index'
    }

    def ldapsearch(String q) {
        if (!ldapService || !q) {
            redirect action: 'index'
            return
        }

        List<String> dns
        if (params.boolean('literal')) {
            dns = ldapService.findByNameLiteral(q)
        } else {
            dns = ldapService.findByName(q)
        }
        render contentType: 'text/plain', text: dns.join('\n')
    }

    def ldapdepartments(String departments) {
        if (!ldapService || !departments) {
            redirect action: 'index'
            return
        }

        List<String> dns
        if (params.boolean('literal')) {
            dns = ldapService.findByDepartmentsLiteral(departments)
        } else {
            dns = ldapService.findByDepartments(departments)
        }
        render contentType: 'text/plain', text: dns.join('\n')
    }

    def ldapattr(String attr, String value) {
        if (!ldapService || !attr || !value) {
            redirect action: 'index'
            return
        }

        List<String> dns
        if (params.boolean('literal')) {
            dns = ldapService.findByAttributeLiteral(attr, value)
        } else {
            dns = ldapService.findByAttribute(attr, value)
        }
        render contentType: 'text/plain', text: dns.join('\n')
    }

    def ldapshow(String cn) {
        if (!ldapService || !cn) {
            redirect action: 'index'
            return
        }

        Map entry
        if (params.boolean('escape')) {
            entry = ldapService.readByNameEscaped(cn)
        } else {
            entry = ldapService.readByName(cn)
        }
        if (entry == null) {
            render status: HttpServletResponse.SC_NOT_FOUND
            return
        }
        render contentType: 'text/plain',
            text: entry.collect { k, v -> "${k}: ${v}" }.join('\n')
    }

    def ldapmail(String mail) {
        if (!ldapService || !mail) {
            redirect action: 'index'
            return
        }

        List<String> dns = ldapService.findByMail(
            mail, params.boolean('literal') ?: false
        )
        render contentType: 'text/plain', text: dns.join('\n')
    }

    def wildcard(String q) {
        if (!q) {
            redirect action: 'index'
            return
        }

        Pattern pattern
        if (params.boolean('literal')) {
            String collapsed = q.replaceAll(/\*+/, '*')
            if (collapsed.count('*') > 3) {
                render status: 400, contentType: 'text/plain', text: 'Too many wildcards'
                return
            }
            String quoted = collapsed.split(/\*/, -1).collect { Pattern.quote(it) }.join('.*')
            pattern = Pattern.compile(quoted)    // SAFE_SINK: PLANTED-GR-HR-352-safe
        } else {
            pattern = Pattern.compile(q.replace('*', '.*'))    // SINK: PLANTED-GR-HR-352
        }

        List<String> names = Person.list(sort: 'lastName').findAll {
            pattern.matcher(it.lastName ?: '').matches()
        }.collect { "${it.lastName}, ${it.firstName}".toString() }
        render contentType: 'text/plain', text: names.join('\n')
    }

    def handleAuthenticationException(AuthenticationException e) {
        handleLdapException 'authentication'
    }

    def handleConnectException(CommunicationException e) {
        handleLdapException 'communication'
    }

    def handleNameNotFoundException(NameNotFoundException e) {
        handleLdapException 'nameNotFound'
    }


    //-- Non-public methods ---------------------

    /**
     * Gets the IDs of the {@code Rating} instances which should be exclude
     * from synchronization.
     *
     * @return  a list of IDs of {@code Rating} instance that should be
     *          excluded from synchronization
     * @since   2.0
     */
    private List<Long> getExcludeFromSyncValues() {
        Credential credential = (Credential) session.credential
        List<Long> ids = credential.settings.excludeFromSync?.split(/,/)?.
            collect { it as Long }

        ids ?: []
    }

    /**
     * Checks whether or not the given person should be excluded from
     * synchronization.
     *
     * @param p     the given person
     * @param ids   a list of IDs of {@code Rating} instance that should be
     *              excluded from synchronization
     * @return      {@code true} if the given person should be excluded from
     *              synchronization; {@code false} otherwise
     * @since       2.0
     */
    private boolean isExcludeFromSync(Person p,
                                      List<Long> ids = excludeFromSyncValues)
    {
        ids.contains p.organization.rating?.id
    }

    /**
     * Handles the given type of exception that occurred while accessing the
     * LDAP service.  The method collects all necessary information and
     * redirects to an error page.
     *
     * @param type  the type of exception that has been occurred
     */
    private def handleLdapException(String type) {
        def origId = (actionName == 'save') ? request.personInstance.id \
            : params.id

        redirect controller: 'error', action: 'ldapPerson', params: [
                type: type, origAction: actionName, personId: origId
            ]
    }
}
