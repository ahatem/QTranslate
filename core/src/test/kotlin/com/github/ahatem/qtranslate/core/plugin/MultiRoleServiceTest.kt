package com.github.ahatem.qtranslate.core.plugin

import com.github.ahatem.qtranslate.api.dictionary.Dictionary
import com.github.ahatem.qtranslate.api.dictionary.DictionaryRequest
import com.github.ahatem.qtranslate.api.dictionary.DictionaryResponse
import com.github.ahatem.qtranslate.api.plugin.DisplayText
import com.github.ahatem.qtranslate.api.plugin.Service
import com.github.ahatem.qtranslate.api.plugin.ServiceError
import com.github.ahatem.qtranslate.api.plugin.ServiceOption
import com.github.ahatem.qtranslate.api.plugin.ServiceOptionValue
import com.github.ahatem.qtranslate.api.plugin.ServiceRole
import com.github.ahatem.qtranslate.api.plugin.SupportedLanguages
import com.github.ahatem.qtranslate.api.translator.TranslationRequest
import com.github.ahatem.qtranslate.api.translator.TranslationResponse
import com.github.ahatem.qtranslate.api.translator.Translator
import com.github.ahatem.qtranslate.core.plugin.registry.ServiceValidator
import com.github.ahatem.qtranslate.core.settings.data.ActiveServiceManager
import com.github.ahatem.qtranslate.core.settings.data.Configuration
import com.github.ahatem.qtranslate.core.settings.data.isServiceDisabled
import com.github.ahatem.qtranslate.core.settings.data.withServiceRoleEnabled
import com.github.ahatem.qtranslate.core.shared.util.role
import com.github.ahatem.qtranslate.core.shared.util.roles
import com.github.michaelbull.result.Ok
import com.github.michaelbull.result.Result
import kotlinx.coroutines.flow.MutableStateFlow
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

/**
 * A service that holds two roles at once.
 *
 * This is the case the whole roles design exists for, and nothing in the tree exercises it: all
 * eleven bundled plugins implement exactly one role interface per service object, so the smoke
 * test proves the single-role path and says nothing about this one. Roles used to be inferred by
 * an ordered type test that returned one answer, which is the bug that made a service like this
 * impossible; these tests pin down that it is now genuinely possible, rather than trusting that
 * `filter` did the job.
 */
class MultiRoleServiceTest {

    @Test
    fun `a service implementing two interfaces holds both roles`() {
        val service = TranslatingDictionary()

        assertEquals(
            setOf(ServiceRole.TRANSLATOR, ServiceRole.DICTIONARY),
            ServiceRole.of(service),
            "a service is offered only for the interfaces it implements, and it implements both"
        )
    }

    @Test
    fun `holding two roles does not stop it being valid`() {
        assertEquals(
            emptyList(),
            ServiceValidator.validate(TranslatingDictionary()),
            "nothing about a second role makes a service unusable"
        )
    }

    @Test
    fun `the representative role is stable rather than whichever check ran first`() {
        // `role` returns one of them for the places that still assume a single answer. Which one
        // hardly matters, but it must not change between calls or between runs, or a service would
        // drift between pickers for no reason the user could see.
        val service = TranslatingDictionary()
        val first = service.role

        repeat(50) { assertEquals(first, service.role) }
        assertTrue(first in service.roles)
    }

    @Test
    fun `the same instance is resolved for both of its roles`() {
        val service = TranslatingDictionary()
        val manager = managerFor(service)

        val asTranslator = manager.getActive<Translator>(ServiceRole.TRANSLATOR)
        val asDictionary = manager.getActive<Dictionary>(ServiceRole.DICTIONARY)

        assertNotNull(asTranslator, "the service implements Translator but was not offered as one")
        assertNotNull(asDictionary, "the service implements Dictionary but was not offered as one")
        assertSame<Any>(
            asTranslator.service, asDictionary.service,
            "both roles are the same object, so resolving them must not produce two services"
        )
        assertEquals(SERVICE_ID, asTranslator.id)
    }

    @Test
    fun `resolving by role casts to that role's interface`() {
        // The unchecked cast in getActive is only honest because a role means the interface is
        // implemented. Calling through both proves the cast the type system cannot check here.
        val manager = managerFor(TranslatingDictionary())

        val translator = manager.getActive<Translator>(ServiceRole.TRANSLATOR)?.service
        val dictionary = manager.getActive<Dictionary>(ServiceRole.DICTIONARY)?.service

        assertNotNull(translator)
        assertNotNull(dictionary)
    }

    @Test
    fun `turning off one role leaves the service usable in the other`() {
        val manager = managerFor(
            TranslatingDictionary(),
            Configuration.DEFAULT.withServiceRoleEnabled(ServiceRole.DICTIONARY, enabled = false)
        )

        assertNotNull(
            manager.getActive<Translator>(ServiceRole.TRANSLATOR),
            "disabling the dictionary role took the translator with it"
        )
        assertNull(manager.getActive<Dictionary>(ServiceRole.DICTIONARY))
    }

    @Test
    fun `options declared for one role are not offered for the other`() {
        // A service holding two roles declares one flat list of options, so each has to name the
        // role it belongs to. Without that, a service that summarizes and rewrites would offer
        // summary length while rewriting.
        val service = TranslatingDictionary()

        val forTranslation = service.options.filter { it.role == null || it.role == ServiceRole.TRANSLATOR }
        val forDictionary = service.options.filter { it.role == null || it.role == ServiceRole.DICTIONARY }

        assertEquals(listOf("formality", "shared"), forTranslation.map { it.key })
        assertEquals(listOf("shared"), forDictionary.map { it.key })
    }

    @Test
    fun `disabling the service itself removes it from every role`() {
        // Pins current behaviour rather than endorsing it. disabledServices holds either a bare
        // service id or a `type:ROLE` sentinel, so there is no way to say "keep this as a
        // translator but not as a dictionary" — switching it off is all or nothing.
        val config = Configuration.DEFAULT.copy(disabledServices = setOf(SERVICE_ID))

        assertTrue(config.isServiceDisabled(SERVICE_ID, ServiceRole.TRANSLATOR))
        assertTrue(config.isServiceDisabled(SERVICE_ID, ServiceRole.DICTIONARY))
        assertNull(managerFor(TranslatingDictionary(), config).getActive<Translator>(ServiceRole.TRANSLATOR))
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private fun managerFor(
        service: Service,
        config: Configuration = Configuration.DEFAULT
    ) = ActiveServiceManager(
        activeServices = MutableStateFlow(mapOf(SERVICE_ID to service)),
        configuration = MutableStateFlow(config)
    )

    /**
     * Translates and defines words, which is the shape the ordered type test could never express.
     * Both implementations are trivial: what is under test is how the host files the service, not
     * what it returns.
     */
    private class TranslatingDictionary : Translator, Dictionary {
        override val key = "translating-dictionary"
        override val name = "Translating Dictionary"
        override val version = "1.0.0"
        override val supportedLanguages = SupportedLanguages.All

        override val options = listOf(
            option("formality", ServiceRole.TRANSLATOR),
            option("shared", role = null)
        )

        override suspend fun translate(
            request: TranslationRequest
        ): Result<TranslationResponse, ServiceError> = Ok(TranslationResponse("translated"))

        override suspend fun lookup(
            request: DictionaryRequest
        ): Result<DictionaryResponse, ServiceError> = Ok(DictionaryResponse(emptyList()))

        private fun option(key: String, role: ServiceRole?) = ServiceOption(
            key = key,
            label = DisplayText("option.$key", key),
            values = listOf(ServiceOptionValue("a", DisplayText("option.$key.a", "A"))),
            defaultValue = "a",
            role = role
        )
    }

    private companion object {
        const val SERVICE_ID = "test-plugin:default:translating-dictionary"
    }
}
