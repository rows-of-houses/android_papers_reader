package com.papersreader.app.data.repository

import com.papersreader.app.data.network.OpenAlexAuthor
import com.papersreader.app.data.network.OpenAlexAuthorship
import com.papersreader.app.data.network.OpenAlexClient
import com.papersreader.app.data.network.OpenAlexLocation
import com.papersreader.app.data.network.OpenAlexOpenAccess
import com.papersreader.app.data.network.OpenAlexWork
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitedByRepositoryTest {

    private fun work(
        id: String = "https://openalex.org/p1",
        title: String? = "Some Paper",
        year: Int? = 2020,
        authors: List<OpenAlexAuthor> = emptyList(),
        doi: String? = null,
        pdfUrl: String? = null,
        oaLandingUrl: String? = null,
    ) = OpenAlexWork(
        id = id,
        title = title,
        publicationYear = year,
        authorships = authors.map { OpenAlexAuthorship(author = it) },
        doi = doi,
        bestOaLocation = pdfUrl?.let { OpenAlexLocation(pdfUrl = it) },
        openAccess = oaLandingUrl?.let { OpenAlexOpenAccess(oaUrl = it) },
    )

    private fun repositoryWith(vararg works: OpenAlexWork): CitedByRepository {
        val client = mockk<OpenAlexClient>()
        coEvery { client.findWorkId(any()) } returns "resolved-id"
        coEvery { client.fetchCitingWorks("resolved-id") } returns works.toList()
        return CitedByRepository(client)
    }

    private fun CitedByOutcome.foundPapers(): List<CitingPaper> {
        check(this is CitedByOutcome.Found) { "expected Found, got $this" }
        return papers
    }

    @Test
    fun `prefers the DOI link over an open-access PDF url`() = runTest {
        val repo = repositoryWith(work(doi = "https://doi.org/10.1/abc", pdfUrl = "https://example.com/paper.pdf"))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://doi.org/10.1/abc", result.single().link)
    }

    @Test
    fun `strips the doi_org prefix off the bare doi field`() = runTest {
        val repo = repositoryWith(work(doi = "https://doi.org/10.1/abc"))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("10.1/abc", result.single().doi)
    }

    @Test
    fun `falls back to the open-access PDF url when there is no DOI`() = runTest {
        val repo = repositoryWith(work(doi = null, pdfUrl = "https://example.com/paper.pdf"))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://example.com/paper.pdf", result.single().link)
    }

    @Test
    fun `falls back to the open-access landing url when there is no DOI or PDF url`() = runTest {
        val repo = repositoryWith(work(doi = null, pdfUrl = null, oaLandingUrl = "https://example.com/landing"))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://example.com/landing", result.single().link)
    }

    @Test
    fun `falls back to the OpenAlex work page when nothing else is present`() = runTest {
        val repo = repositoryWith(work(id = "https://openalex.org/W42", doi = null, pdfUrl = null, oaLandingUrl = null))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://openalex.org/W42", result.single().link)
    }

    @Test
    fun `authorsDisplay is null when there are no authors`() = runTest {
        val repo = repositoryWith(work(authors = emptyList()))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertNull(result.single().authorsDisplay)
    }

    @Test
    fun `authorsDisplay joins multiple author names`() = runTest {
        val repo = repositoryWith(work(authors = listOf(OpenAlexAuthor("A. One"), OpenAlexAuthor("B. Two"))))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("A. One, B. Two", result.single().authorsDisplay)
    }

    @Test
    fun `papers with a null title are dropped`() = runTest {
        val repo = repositoryWith(
            work(id = "https://openalex.org/keep", title = "Kept"),
            work(id = "https://openalex.org/drop", title = null),
        )
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals(listOf("keep"), result.map { it.id })
    }

    @Test
    fun `an unresolvable title yields Unavailable, not an empty Found list`() = runTest {
        val client = mockk<OpenAlexClient>()
        coEvery { client.findWorkId(any()) } returns null
        val repo = CitedByRepository(client)
        assertEquals(CitedByOutcome.Unavailable, repo.findCitingPapers("Unfindable Title"))
    }

    @Test
    fun `a failed citations fetch yields Unavailable, not an empty Found list`() = runTest {
        val client = mockk<OpenAlexClient>()
        coEvery { client.findWorkId(any()) } returns "resolved-id"
        coEvery { client.fetchCitingWorks("resolved-id") } returns null
        val repo = CitedByRepository(client)
        assertEquals(CitedByOutcome.Unavailable, repo.findCitingPapers("Some Title"))
    }

    @Test
    fun `a resolved paper with genuinely zero citations yields an empty Found list`() = runTest {
        val client = mockk<OpenAlexClient>()
        coEvery { client.findWorkId(any()) } returns "resolved-id"
        coEvery { client.fetchCitingWorks("resolved-id") } returns emptyList()
        val repo = CitedByRepository(client)
        assertTrue(repo.findCitingPapers("Some Title").foundPapers().isEmpty())
    }
}
