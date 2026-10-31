package com.github.ahatem.qtranslate.core.document

import com.github.ahatem.qtranslate.api.language.LanguageCode
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals

@OptIn(ExperimentalCoroutinesApi::class)
class DocumentTranslationSessionTest {
    private fun request(name: String) = DocumentTranslationRequest(
        File("$name.txt"), File("$name-out.txt"), LanguageCode("en"), LanguageCode("fr")
    )

    @Test fun `superseded job cannot emit progress or completion`() = runTest {
        val releases = listOf(CompletableDeferred<Unit>(), CompletableDeferred())
        val updates = mutableListOf<DocumentTranslationProgress?>()
        val completed = mutableListOf<File>()
        var calls = 0
        val session = DocumentTranslationSession(this, { request, progress ->
            val index = calls++
            withContext(NonCancellable) {
                releases[index].await()
                progress(DocumentTranslationProgress(index + 1, 2))
                request.outputFile
            }
        }, updates::add, completed::add, { error(it) })

        session.start(request("old"))
        runCurrent()
        session.start(request("new"))
        runCurrent()
        releases[0].complete(Unit)
        runCurrent()
        assertEquals(emptyList(), updates)
        assertEquals(emptyList(), completed)
        releases[1].complete(Unit)
        runCurrent()
        assertEquals(listOf(DocumentTranslationProgress(2, 2), null), updates)
        assertEquals(listOf(File("new-out.txt")), completed)
    }

    @Test fun `cancel clears progress and suppresses late updates`() = runTest {
        val release = CompletableDeferred<Unit>()
        val updates = mutableListOf<DocumentTranslationProgress?>()
        val completed = mutableListOf<File>()
        val session = DocumentTranslationSession(this, { request, progress ->
            progress(DocumentTranslationProgress(0, 1))
            withContext(NonCancellable) {
                release.await()
                progress(DocumentTranslationProgress(1, 1))
                request.outputFile
            }
        }, updates::add, completed::add, { error(it) })
        session.start(request("cancel"))
        runCurrent()
        session.cancel()
        release.complete(Unit)
        runCurrent()
        assertEquals(listOf(DocumentTranslationProgress(0, 1), null), updates)
        assertEquals(emptyList(), completed)
    }
}
