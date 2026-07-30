package com.papersreader.app.data.repository

import com.papersreader.app.data.db.BrowserTabDao
import com.papersreader.app.data.db.BrowserTabEntity
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BrowserTabRepository @Inject constructor(
    private val browserTabDao: BrowserTabDao,
) {
    fun observeTabs(): Flow<List<BrowserTabEntity>> = browserTabDao.observeAll()

    /** Opens a new tab (e.g. following a reference link) and makes it the active one. */
    suspend fun openNewTab(url: String, title: String? = null): Long {
        val position = browserTabDao.count()
        browserTabDao.clearActive()
        return browserTabDao.insert(
            BrowserTabEntity(
                url = url,
                title = title,
                position = position,
                isActive = true,
                createdAt = System.currentTimeMillis(),
            )
        )
    }

    suspend fun setActive(tabId: Long) {
        browserTabDao.clearActive()
        browserTabDao.markActive(tabId)
    }

    suspend fun updateTab(tab: BrowserTabEntity) = browserTabDao.update(tab)

    /**
     * Closes a tab. If it was the last one, opens a fresh tab so the browser is never empty;
     * if it was the active tab but others remain, promotes the first remaining tab to active.
     */
    suspend fun closeTab(tab: BrowserTabEntity, fallbackUrl: String, fallbackTitle: String?) {
        browserTabDao.delete(tab)
        if (browserTabDao.count() == 0) {
            openNewTab(fallbackUrl, fallbackTitle)
        } else if (browserTabDao.activeCount() == 0) {
            browserTabDao.firstTabId()?.let { browserTabDao.markActive(it) }
        }
    }

    suspend fun closeAll() = browserTabDao.deleteAll()

    /** Opens a first tab if the user has never browsed before / closed every tab. */
    suspend fun ensureAtLeastOneTab(defaultUrl: String, defaultTitle: String?) {
        if (browserTabDao.count() == 0) {
            openNewTab(defaultUrl, defaultTitle)
        }
    }
}
