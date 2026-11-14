package vade.ui.data

import vade.ui.protocol.VadeErrorCodes
import vade.ui.protocol.VadeException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The 34 frozen C-04 error names must ALL have exactly one UI bucket (a missing entry
 * turns this red — the map is the single classification table from c-data §4).
 */
class VadeErrorMappingTest {

    @Test
    fun everyDeclaredErrorCodeHasExactlyOneMapping() {
        assertEquals(VadeErrorCodes.ALL.toSet(), VadeErrorMapping.categories.keys)
        assertEquals(34, VadeErrorMapping.categories.size)
        assertEquals(34, VadeErrorCodes.ALL.size)
    }

    @Test
    fun categoryOfNeverThrowsAndIsDefinedForEveryDeclaredCode() {
        VadeErrorCodes.ALL.forEach { code ->
            val category = VadeErrorMapping.categoryOf(code)
            assertTrue(
                category in VadeErrorCategory.entries,
                "code $code must map to one of the four buckets, got $category",
            )
        }
    }

    @Test
    fun representativeCodesLandInTheExpectedBucket() {
        assertEquals(VadeErrorCategory.Retryable, VadeErrorMapping.categoryOf(VadeErrorCodes.TIMEOUT))
        assertEquals(VadeErrorCategory.Retryable, VadeErrorMapping.categoryOf(VadeErrorCodes.OVERLOADED))
        assertEquals(VadeErrorCategory.NeedsReconnect, VadeErrorMapping.categoryOf(VadeErrorCodes.UNAVAILABLE))
        assertEquals(VadeErrorCategory.NeedsReconnect, VadeErrorMapping.categoryOf(VadeErrorCodes.AUTH_FAILED))
        assertEquals(VadeErrorCategory.NeedsUserAction, VadeErrorMapping.categoryOf(VadeErrorCodes.INVALID_ARGUMENT))
        assertEquals(VadeErrorCategory.NeedsUserAction, VadeErrorMapping.categoryOf(VadeErrorCodes.BUDGET_EXCEEDED))
        assertEquals(VadeErrorCategory.Fatal, VadeErrorMapping.categoryOf(VadeErrorCodes.PROTOCOL_MISMATCH))
        assertEquals(VadeErrorCategory.Fatal, VadeErrorMapping.categoryOf(VadeErrorCodes.ORIGIN_MISMATCH))
    }

    @Test
    fun clientSideRaisedCodesReuseDeclaredNamesAndMap() {
        // The client raises TIMEOUT for its own request timeout and UNAVAILABLE when a
        // transport dies mid-request — both reuse declared C-04 names, so they must map.
        assertEquals(VadeErrorCategory.Retryable, VadeErrorMapping.categoryOf(VadeException.CLIENT_TIMEOUT_CODE))
        assertEquals(VadeErrorCategory.NeedsReconnect, VadeErrorMapping.categoryOf(VadeException.TRANSPORT_CLOSED_CODE))
    }

    @Test
    fun unknownFutureCodeFallsBackToRetryable() {
        assertEquals(VadeErrorCategory.Retryable, VadeErrorMapping.categoryOf("FUTURE_CODE"))
        assertEquals(VadeErrorCategory.Retryable, VadeErrorMapping.categoryOf(""))
    }
}
