package app.mealmapper.data.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeepgramParsingTest {
    @Test fun urlUsesNova3MultilingualAndRepeatsKeyterm() {
        val url = DeepgramParsing.listenUrl(listOf("gatte ki sabzi", "roti"))
        assertTrue(url.startsWith("https://api.deepgram.com/v1/listen?model=nova-3&language=multi"))
        assertTrue(url.contains("&keyterm=gatte+ki+sabzi&keyterm=roti"))
        assertTrue(!url.contains(","))
    }

    @Test fun keytermsStayWellUnderTheTokenLimit() =
        assertTrue(DeepgramParsing.KEYTERMS.sumOf { it.split(' ').size } < 200)

    @Test fun readsCodeSwitchedTranscript() {
        val body = """{"metadata":{"duration":3.1},"results":{"channels":[{"alternatives":[
            {"transcript":"gatte ki sabzi with two wheat rotis","confidence":0.97,"languages":["hi","en"],"words":[]}]}]}}"""
        assertEquals("gatte ki sabzi with two wheat rotis", DeepgramParsing.transcript(body))
    }

    @Test fun silenceGivesEmptyTranscript() =
        assertEquals("", DeepgramParsing.transcript("""{"results":{"channels":[{"alternatives":[{"transcript":""}]}]}}"""))

    @Test fun errorMessage() =
        assertEquals("Invalid credentials.", DeepgramParsing.errorMessage("""{"err_code":"INVALID_AUTH","err_msg":"Invalid credentials."}"""))
}
