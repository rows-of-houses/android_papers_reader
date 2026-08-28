package com.papersreader.app.data.repository

import com.papersreader.app.data.network.SemanticScholarAuthor
import com.papersreader.app.data.network.SemanticScholarClient
import com.papersreader.app.data.network.SemanticScholarExternalIds
import com.papersreader.app.data.network.SemanticScholarOpenAccessPdf
import com.papersreader.app.data.network.SemanticScholarPaper
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CitedByRepositoryTest {

    private fun paper(
        id: String = "p1",
        title: String? = "Some Paper",
        year: Int? = 2020,
        authors: List<SemanticScholarAuthor> = emptyList(),
        doi: String? = null,
        openAccessPdfUrl: String? = null,
    ) = SemanticScholarPaper(
        paperId = id,
        title = title,
        year = year,
        authors = authors,
        externalIds = doi?.let { SemanticScholarExternalIds(DOI = it) },
        openAccessPdf = openAccessPdfUrl?.let { SemanticScholarOpenAccessPdf(url = it) },
    )

    private fun repositoryWith(vararg papers: SemanticScholarPaper): CitedByRepository {
        val client = mockk<SemanticScholarClient>()
        coEvery { client.findPaperId(any()) } returns "resolved-id"
        coEvery { client.fetchCitingPapers("resolved-id") } returns papers.toList()
        return CitedByRepository(client)
    }

    private fun CitedByOutcome.foundPapers(): List<CitingPaper> {
        check(this is CitedByOutcome.Found) { "expected Found, got $this" }
        return papers
    }

    @Test
    fun `prefers the DOI link over an open-access PDF url`() = runTest {
        val repo = repositoryWith(paper(doi = "10.1/abc", openAccessPdfUrl = "https://example.com/paper.pdf"))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://doi.org/10.1/abc", result.single().link)
    }

    @Test
    fun `falls back to the open-access PDF url when there is no DOI`() = runTest {
        val repo = repositoryWith(paper(doi = null, openAccessPdfUrl = "https://example.com/paper.pdf"))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://example.com/paper.pdf", result.single().link)
    }

    @Test
    fun `falls back to the Semantic Scholar paper page when neither DOI nor OA url is present`() = runTest {
        val repo = repositoryWith(paper(id = "p42", doi = null, openAccessPdfUrl = null))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("https://www.semanticscholar.org/paper/p42", result.single().link)
    }

    @Test
    fun `authorsDisplay is null when there are no authors`() = runTest {
        val repo = repositoryWith(paper(authors = emptyList()))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertNull(result.single().authorsDisplay)
    }

    @Test
    fun `authorsDisplay joins multiple author names`() = runTest {
        val repo = repositoryWith(paper(authors = listOf(SemanticScholarAuthor("A. One"), SemanticScholarAuthor("B. Two"))))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals("A. One, B. Two", result.single().authorsDisplay)
    }

    @Test
    fun `papers with a null title are dropped`() = runTest {
        val repo = repositoryWith(paper(id = "keep", title = "Kept"), paper(id = "drop", title = null))
        val result = repo.findCitingPapers("Some Title").foundPapers()
        assertEquals(listOf("keep"), result.map { it.id })
    }

    @Test
    fun `an unresolvable title yields Unavailable, not an empty Found list`() = runTest {
        val client = mockk<SemanticScholarClient>()
        coEvery { client.findPaperId(any()) } returns null
        val repo = CitedByRepository(client)
        assertEquals(CitedByOutcome.Unavailable, repo.findCitingPapers("Unfindable Title"))
    }

    @Test
    fun `a failed citations fetch yields Unavailable, not an empty Found list`() = runTest {
        val client = mockk<SemanticScholarClient>()
        coEvery { client.findPaperId(any()) } returns "resolved-id"
        coEvery { client.fetchCitingPapers("resolved-id") } returns null
        val repo = CitedByRepository(client)
        assertEquals(CitedByOutcome.Unavailable, repo.findCitingPapers("Some Title"))
    }

    @Test
    fun `a resolved paper with genuinely zero citations yields an empty Found list`() = runTest {
        val client = mockk<SemanticScholarClient>()
        coEvery { client.findPaperId(any()) } returns "resolved-id"
        coEvery { client.fetchCitingPapers("resolved-id") } returns emptyList()
        val repo = CitedByRepository(client)
        assertTrue(repo.findCitingPapers("Some Title").foundPapers().isEmpty())
    }
}
