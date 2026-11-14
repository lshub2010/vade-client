package vade.ui.data

import kotlinx.browser.window

/**
 * Wasm actual of the raw-source channel: the URL query
 * (`index.html?source=real&host=127.0.0.1&port=18744&pair_code=...`). The grammar
 * lives in the shared [rawStringsFromQuery]; a browser never sends env vars, so the
 * query is the whole channel. The pair code rides the URL ONLY for a local demo —
 * a page shell pair flow (page shell → `POST /ui/session`) is the real deployment
 * path and stays future work (see shots/g-live/PLAYBOOK.md · 卡点).
 */
actual fun rawSourceStrings(): RawSourceStrings =
    rawStringsFromQuery(window.location.search)
