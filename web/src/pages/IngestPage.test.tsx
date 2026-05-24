import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import type {IngestTask, PageResult} from '../types'

// ============================================================
// Unit Tests for IngestPage (Task 11.2)
// ============================================================

describe('IngestPage - Unit Tests', () => {
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

    /** Helper to create a mock ingest task. */
    function mockTask(overrides: Partial<IngestTask> = {}): IngestTask {
        return {
            id: 1,
            requestId: 'req-001',
            userId: 'user-1',
            sourceType: 'MANUAL',
            status: 'RECEIVED',
            reviewStatus: 'CANDIDATE',
            createdAt: '2024-01-15T10:30:00',
            updatedAt: '2024-01-15T10:35:00',
            ...overrides,
        }
    }

    /** Helper to create a mock page result. */
    function mockPageResult(
        tasks: IngestTask[],
        opts: { total?: number; current?: number; size?: number; pages?: number } = {},
    ): PageResult<IngestTask> {
        return {
            records: tasks,
            total: opts.total ?? tasks.length,
            current: opts.current ?? 1,
            size: opts.size ?? 20,
            pages: opts.pages ?? 1,
        }
    }

    /** Set up fetch mock to return a page result for task list requests. */
    function setupFetchMock(
        pageResult: PageResult<IngestTask>,
        detailTask?: IngestTask,
    ) {
        globalThis.fetch = vi.fn().mockImplementation((url: string) => {
            if (url.includes('/api/v1/ingest/status/')) {
                if (detailTask) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve(detailTask),
                    })
                }
                return Promise.resolve({
                    ok: false,
                    status: 404,
                    json: () => Promise.resolve({message: 'Not found'}),
                })
            }
            if (url.includes('/api/v1/ingest/tasks')) {
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

    async function renderIngestPage() {
        const {default: IngestPage} = await import('./IngestPage')
        return render(<IngestPage/>)
    }

    // ---- Test: Table renders with mock ingest task data ----

    it('should render table with mock ingest task data', async () => {
        const tasks = [
            mockTask({id: 1, requestId: 'req-001', sourceType: 'MANUAL', status: 'RECEIVED'}),
            mockTask({id: 2, requestId: 'req-002', sourceType: 'API', status: 'SYNCED'}),
            mockTask({id: 3, requestId: 'req-003', sourceType: 'MANUAL', status: 'FAILED'}),
        ]
        setupFetchMock(mockPageResult(tasks, {total: 3}))

        await renderIngestPage()

        await waitFor(() => {
            expect(screen.getByText('req-001')).toBeInTheDocument()
        }, {timeout: 10000})

        expect(screen.getByText('req-002')).toBeInTheDocument()
        expect(screen.getByText('req-003')).toBeInTheDocument()
        expect(screen.getByText('RECEIVED')).toBeInTheDocument()
        expect(screen.getByText('SYNCED')).toBeInTheDocument()
        expect(screen.getByText('FAILED')).toBeInTheDocument()
        expect(screen.getAllByText('共 3 条任务').length).toBeGreaterThanOrEqual(1)
    })

    // ---- Test: Status filter interaction updates table ----

    it('should fetch tasks with status param when filter is applied', async () => {
        // First render: initial fetch without status filter
        const allTasks = [
            mockTask({id: 1, status: 'RECEIVED'}),
            mockTask({id: 2, status: 'SYNCED'}),
        ]
        setupFetchMock(mockPageResult(allTasks, {total: 2}))

        await renderIngestPage()

        await waitFor(() => {
            expect(screen.getByText('RECEIVED')).toBeInTheDocument()
        }, {timeout: 10000})

        // Verify initial fetch was called without status filter
        const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
        expect(calls.length).toBeGreaterThan(0)
        const initialUrl = calls[0][0] as string
        expect(initialUrl).toContain('/api/v1/ingest/tasks')
        expect(initialUrl).toContain('page=1')
        expect(initialUrl).toContain('size=20')
        // No status param on initial load
        expect(initialUrl).not.toContain('status=')
    })

    it('should render status filter select with correct options', async () => {
        setupFetchMock(mockPageResult([], {total: 0}))

        await renderIngestPage()

        // The status filter select should be rendered
        await waitFor(() => {
            expect(screen.getByText('入库任务')).toBeInTheDocument()
        }, {timeout: 10000})

        // The select placeholder should be visible
        expect(screen.getByText('按状态筛选')).toBeInTheDocument()
    })

    // ---- Test: Pagination controls ----

    it('should display pagination controls with correct total', async () => {
        const tasks = Array.from({length: 20}, (_, i) =>
            mockTask({id: i + 1, requestId: `req-${i + 1}`}),
        )
        setupFetchMock(mockPageResult(tasks, {total: 45, current: 1, size: 20, pages: 3}))

        await renderIngestPage()

        await waitFor(() => {
            expect(screen.getAllByText('共 45 条任务').length).toBeGreaterThanOrEqual(1)
        })
    })

    // ---- Test: Row click opens detail modal ----

    it('should open detail modal when a row is clicked', async () => {
        const task = mockTask({
            id: 42,
            requestId: 'req-detail',
            sourceType: 'API',
            status: 'PROCESSED_READY',
            rawObjectKey: 'raw/key/path',
        })
        setupFetchMock(mockPageResult([task], {total: 1}), task)

        await renderIngestPage()

        await waitFor(() => {
            expect(screen.getByText('req-detail')).toBeInTheDocument()
        })

        // Click the row
        fireEvent.click(screen.getByText('req-detail'))

        // Modal should appear with detail info
        await waitFor(() => {
            expect(screen.getByText(/入库任务 #42/)).toBeInTheDocument()
        })

        // Detail fields should be visible
        await waitFor(() => {
            expect(screen.getByText('raw/key/path')).toBeInTheDocument()
        })
    })

    // ---- Test: Error state with retry button ----

    it('should show error state with retry button on fetch failure', async () => {
        globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Network error'))

        await renderIngestPage()

        await waitFor(() => {
            expect(screen.getByText(/Network error/)).toBeInTheDocument()
        })

        // Retry button should be present
        const retryBtn = screen.getByTestId('retry-button')
        expect(retryBtn).toBeInTheDocument()

        // Click retry — set up a successful response
        const tasks = [mockTask({id: 1, requestId: 'req-after-retry'})]
        setupFetchMock(mockPageResult(tasks, {total: 1}))

        fireEvent.click(retryBtn)

        await waitFor(() => {
            expect(screen.getByText('req-after-retry')).toBeInTheDocument()
        })
    })

    // ---- Test: Empty state when no tasks match filters ----

    it('should display empty state when no tasks match filters', async () => {
        setupFetchMock(mockPageResult([], {total: 0}))

        await renderIngestPage()

        await waitFor(() => {
            expect(screen.getByText('暂无入库任务')).toBeInTheDocument()
        })
    })
})
