package com.nuvio.app.features.media.service

import kotlin.test.*

class WebDavListingTest {
    @Test fun namespacedCollectionsAndEscapedNamesAreParsed() {
        val xml = """<?xml version="1.0"?><d:multistatus xmlns:d="DAV:"><d:response><d:href>/music/Album/</d:href><d:propstat><d:prop><d:displayname>A &amp; B</d:displayname><d:resourcetype><d:collection/></d:resourcetype></d:prop><d:status>HTTP/1.1 200 OK</d:status></d:propstat></d:response></d:multistatus>"""
        assertEquals(listOf(WebDavListingItem("/music/Album/", "A & B", true)), parseWebDavListing(xml))
    }
    @Test fun failedPropertiesDoNotBecomeLibraryItems() {
        val xml = """<multistatus><response><href>/secret.mp3</href><propstat><prop><displayname>Private</displayname></prop><status>HTTP/1.1 403 Forbidden</status></propstat></response></multistatus>"""
        assertTrue(parseWebDavListing(xml).isEmpty())
    }
    @Test fun externalEntitiesAndDtdAreRejected() {
        assertFailsWith<MediaServiceException> { parseWebDavListing("<!DOCTYPE x [<!ENTITY a SYSTEM 'file:///secret'>]><multistatus/>") }
    }
    @Test fun malformedResponseIsNotEmptySuccess() {
        assertFailsWith<MediaServiceException> { parseWebDavListing("<multistatus><response>") }
    }
    @Test fun unknownEntitiesAreRejected() {
        val xml = """<multistatus><response><href>/a.mp3</href><propstat><prop><displayname>&unknown;</displayname></prop><status>HTTP/1.1 200 OK</status></propstat></response></multistatus>"""
        assertFailsWith<MediaServiceException> { parseWebDavListing(xml) }
    }
}
