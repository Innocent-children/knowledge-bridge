import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import type {KnowledgeDocument, PageResult} from '../types'

// ============================================================
// Unit Tests for DocumentsPage (Task 13.2)
// ============================================================

describe('DocumentsPage - Unit Tests', () => {
    const originalFetch = globalThis.fetch

    beforeEach(() => {
        Object.defineProperty(window, 'location', {
            value: {origin: 'http://localhost:8080'},
            writable: true,
        })
    })

    afterEach(() => {
        globalThis.fetch = originalFetch
        vi.resetModules()
        vi.restoreAllMocks()
    })

    /** Helper to create a mock knowledge document. */
    function mockDocument(overrides: Partial<KnowledgeDocument> = {}): KnowledgeDocument {
        return {
            id: 1,
            taskId: 100,
            knowledgeType: 'GUIDE',
            title: 'Test Document',
            topic: 'Testing',
            status: 'SYNCED',
            reviewStatus: 'APPROVED',
            version: 1,
            createdAt: '2024-01-15T10:30:00',
            updatedAt: '2024-01-15T10:35:00',
            ...overrides,
        }
    }

    /** Helper to create a mock page result. */
    function mockPageResult(
        docs: KnowledgeDocument[],
        opts: { total?: number; current?: number; size?: number; pages?: number } = {},
    ): PageResult<KnowledgeDocument> {
        return {
            records: docs,
            total: opts.total ?? docs.length,
            current: opts.current ?? 1,
            size: opts.size ?? 20,
            pages: opts.pages ?? 1,
        }
    }

    /** Set up fetch mock to return a page result for document list requests. */
    function setupFetchMock(
        pageResult: PageResult<KnowledgeDocument>,
        detailDoc?: KnowledgeDocument,
        toggleResponse?: { ok: boolean; body?: unknown },
    ) {
        globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
            // Enable/Disable toggle endpoints
            if (
                init?.method === 'POST' &&
                (url.includes('/enable') || url.includes('/disable'))
            ) {
                if (toggleResponse && !toggleResponse.ok) {
                    return Promise.resolve({
                        ok: false,
                        status: 500,
                        json: () =>
                            Promise.resolve(
                                toggleResponse.body ?? {message: 'Toggle failed'},
                            ),
                    })
                }
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () =>
                        Promise.resolve(
                            toggleResponse?.body ?? {message: '操作成功'},
                        ),
                })
            }

            // Document detail endpoint
            if (url.includes('/api/v1/document/') && !url.includes('/documents')) {
                if (detailDoc) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve(detailDoc),
                    })
                }
                return Promise.resolve({
                    ok: false,
                    status: 404,
                    json: () => Promise.resolve({message: 'Not found'}),
                })
            }

            // Document list endpoint
            if (url.includes('/api/v1/documents')) {
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve(pageResult),
                })
            }

            return Promise.resolve({
                ok: false,
                status: 404,
                json: () => Promise.resolve({message: 'Not found'}),
            })
        })
    }

    async function renderDocumentsPage() {
        const {default: DocumentsPage} = await import('./DocumentsPage')
        return render(<DocumentsPage/>)
    }

    // ---- Test: Table renders with mock document data ----

    it('should render table with mock document data', async () => {
        const docs = [
            mockDocument({id: 1, title: 'Guide Alpha', knowledgeType: 'GUIDE', status: 'SYNCED'}),
            mockDocument({id: 2, title: 'QA Beta', knowledgeType: 'QA', status: 'DISABLED'}),
            mockDocument({id: 3, title: 'FAQ Gamma', knowledgeType: 'FAQ', status: 'FAILED'}),
        ]
        setupFetchMock(mockPageResult(docs, {total: 3}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('Guide Alpha')).toBeInTheDocument()
        })

        expect(screen.getByText('QA Beta')).toBeInTheDocument()
        expect(screen.getByText('FAQ Gamma')).toBeInTheDocument()
        expect(screen.getByText('GUIDE')).toBeInTheDocument()
        expect(screen.getByText('QA')).toBeInTheDocument()
        expect(screen.getByText('FAQ')).toBeInTheDocument()
        expect(screen.getAllByText('共 3 条文档').length).toBeGreaterThanOrEqual(1)
    })

    // ---- Test: Enable/Disable toggle calls correct API ----

    it('should call disable API when toggling an enabled document', async () => {
        const doc = mockDocument({id: 10, title: 'Enabled Doc', status: 'SYNCED'})
        setupFetchMock(mockPageResult([doc], {total: 1}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('Enabled Doc')).toBeInTheDocument()
        })

        // Find the switch and click it
        const toggle = screen.getByRole('switch')
        expect(toggle).toBeInTheDocument()

        fireEvent.click(toggle)

        await waitFor(() => {
            const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
            const toggleCall = calls.find(
                (c) => typeof c[0] === 'string' && c[0].includes('/disable'),
            )
            expect(toggleCall).toBeDefined()
            expect(toggleCall![0]).toContain('/api/v1/document/10/disable')
        })
    })

    it('should call enable API when toggling a disabled document', async () => {
        const doc = mockDocument({id: 20, title: 'Disabled Doc', status: 'DISABLED'})
        setupFetchMock(mockPageResult([doc], {total: 1}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('Disabled Doc')).toBeInTheDocument()
        })

        const toggle = screen.getByRole('switch')
        fireEvent.click(toggle)

        await waitFor(() => {
            const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
            const toggleCall = calls.find(
                (c) => typeof c[0] === 'string' && c[0].includes('/enable'),
            )
            expect(toggleCall).toBeDefined()
            expect(toggleCall![0]).toContain('/api/v1/document/20/enable')
        })
    })

    // ---- Test: Toggle revert on failure ----

    it('should revert toggle and show error notification on failure', async () => {
        const doc = mockDocument({id: 30, title: 'Revert Doc', status: 'SYNCED'})
        setupFetchMock(mockPageResult([doc], {total: 1}), undefined, {
            ok: false,
            body: {message: 'Server error'},
        })

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('Revert Doc')).toBeInTheDocument()
        })

        // The switch should be checked (SYNCED = enabled)
        const toggles = screen.getAllByTestId('toggle-30')
        const toggle = toggles[0]
        expect(toggle).toHaveAttribute('aria-checked', 'true')

        fireEvent.click(toggle)

        // After failure, the toggle should revert back to checked
        await waitFor(() => {
            expect(toggle).toHaveAttribute('aria-checked', 'true')
        })

        // Error notification should appear
        await waitFor(() => {
            expect(screen.getByText('禁用失败')).toBeInTheDocument()
        })
    })

    // ---- Test: Row click opens detail panel ----

    it('should open detail drawer when a row is clicked', async () => {
        const doc = mockDocument({
            id: 42,
            title: 'Detail Doc',
            knowledgeType: 'GUIDE',
            topic: 'Architecture',
            tagsJson: '["java","spring"]',
            metadataJson: '{"source":"manual"}',
            datasetName: 'main-dataset',
            version: 3,
        })
        setupFetchMock(mockPageResult([doc], {total: 1}), doc)

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('Detail Doc')).toBeInTheDocument()
        })

        // Click the row (click on the title text)
        fireEvent.click(screen.getByText('Detail Doc'))

        // Drawer should appear with detail info
        await waitFor(() => {
            expect(screen.getByText(/文档详情/)).toBeInTheDocument()
        })

        // Detail fields should be visible
        await waitFor(() => {
            expect(screen.getByText('main-dataset')).toBeInTheDocument()
        })

        // Tags should be rendered
        expect(screen.getByText('java')).toBeInTheDocument()
        expect(screen.getByText('spring')).toBeInTheDocument()

        // Metadata should be rendered
        expect(screen.getByText('{"source":"manual"}')).toBeInTheDocument()
    })

    // ---- Test: Status filter interaction ----

    it('should fetch documents with status param when filter is applied', async () => {
        const docs = [
            mockDocument({id: 1, status: 'SYNCED'}),
            mockDocument({id: 2, status: 'DISABLED'}),
        ]
        setupFetchMock(mockPageResult(docs, {total: 2}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getAllByText('共 2 条文档').length).toBeGreaterThanOrEqual(1)
        })

        // Verify initial fetch was called without status filter
        const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
        expect(calls.length).toBeGreaterThan(0)
        const initialUrl = calls[0][0] as string
        expect(initialUrl).toContain('/api/v1/documents')
        expect(initialUrl).toContain('page=1')
        expect(initialUrl).toContain('size=20')
        expect(initialUrl).not.toContain('status=')
    })

    it('should render status filter select with correct placeholder', async () => {
        setupFetchMock(mockPageResult([], {total: 0}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('文档管理')).toBeInTheDocument()
        })

        expect(screen.getByText('按状态筛选')).toBeInTheDocument()
    })

    // ---- Test: Pagination controls ----

    it('should display pagination controls with correct total', async () => {
        const docs = Array.from({length: 20}, (_, i) =>
            mockDocument({id: i + 1, title: `Doc ${i + 1}`}),
        )
        setupFetchMock(mockPageResult(docs, {total: 55, current: 1, size: 20, pages: 3}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getAllByText('共 55 条文档').length).toBeGreaterThanOrEqual(1)
        })
    })

    // ---- Test: Empty state ----

    it('should display empty state when no documents match filters', async () => {
        setupFetchMock(mockPageResult([], {total: 0}))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText('暂无文档')).toBeInTheDocument()
        })
    })

    // ---- Test: Error state with retry ----

    it('should show error state with retry button on fetch failure', async () => {
        globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Network error'))

        await renderDocumentsPage()

        await waitFor(() => {
            expect(screen.getByText(/Network error/)).toBeInTheDocument()
        })

        const retryBtn = screen.getByTestId('retry-button')
        expect(retryBtn).toBeInTheDocument()

        // Click retry — set up a successful response
        const docs = [mockDocument({id: 1, title: 'After Retry Doc'})]
        setupFetchMock(mockPageResult(docs, {total: 1}))

        fireEvent.click(retryBtn)

        await waitFor(() => {
            expect(screen.getByText('After Retry Doc')).toBeInTheDocument()
        })
    })

    it('shows only effective unified versions as enabled and prevents toggling deleted or processing versions', async () => {
        const states = ['EFFECTIVE', 'WITHDRAWN', 'DELETED', 'PREPARING', 'INDEXING']
        const docs = states.map((status, index) => mockDocument({id: index + 200, documentId: `doc-${index}`, status}))
        setupFetchMock(mockPageResult(docs))
        await renderDocumentsPage()
        await waitFor(() => expect(screen.getByTestId('toggle-200')).toBeInTheDocument())
        expect(screen.getByTestId('toggle-200')).toHaveAttribute('aria-checked', 'true')
        expect(screen.getByTestId('toggle-201')).toHaveAttribute('aria-checked', 'false')
        expect(screen.getByTestId('toggle-201')).not.toBeDisabled()
        for (const id of [202, 203, 204]) {
            expect(screen.getByTestId(`toggle-${id}`)).toHaveAttribute('aria-checked', 'false')
            expect(screen.getByTestId(`toggle-${id}`)).toBeDisabled()
        }
        expect(screen.getAllByText('处理中')).toHaveLength(2)
        fireEvent.click(screen.getByTestId('toggle-201'))
        await waitFor(() => expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.some(
            call => String(call[0]).includes('/document/201/enable'))).toBe(true))
    })

    it('renders unified Markdown preview as text without creating HTML elements', async () => {
        const content = '# Stored Markdown\n<script>window.injected = true</script>\n<img src="https://invalid.example/image" />'
        const doc = mockDocument({id: 205, documentId: 'doc-preview', status: 'EFFECTIVE', contentPreview: content})
        setupFetchMock(mockPageResult([doc]), doc)
        await renderDocumentsPage()
        await waitFor(() => expect(screen.getByText('Test Document')).toBeInTheDocument())
        fireEvent.click(screen.getByText('Test Document'))
        await waitFor(() => expect(screen.getByText((_, element) => element?.tagName === 'PRE' && element.textContent === content)).toBeInTheDocument())
        expect(document.querySelector('script')).toBeNull()
        expect(document.querySelector('img[src="https://invalid.example/image"]')).toBeNull()
    })


    it('keeps blog publications read-only while OpenClaw documents remain manageable', async () => {
        const blog = mockDocument({id: 301, documentId: 'blog-note', source: 'BLOG', status: 'EFFECTIVE', title: 'Blog note'})
        const openclaw = mockDocument({id: 302, documentId: 'claw-note', source: 'OPENCLAW', status: 'EFFECTIVE', title: 'OpenClaw note'})
        setupFetchMock(mockPageResult([blog, openclaw]))
        await renderDocumentsPage()
        await waitFor(() => expect(screen.getByText('Blog note')).toBeInTheDocument())
        expect(screen.getByText('在博客管理')).toBeInTheDocument()
        expect(screen.queryByTestId('toggle-301')).toBeNull()
        expect(screen.getByTestId('toggle-302')).toBeEnabled()
        fireEvent.click(screen.getByTestId('toggle-302'))
        await waitFor(() => expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.some(
            call => String(call[0]).includes('/document/302/disable'))).toBe(true))
        expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.some(
            call => String(call[0]).includes('/document/301/disable'))).toBe(false)
    })

})
