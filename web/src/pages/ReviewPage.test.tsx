import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor, within} from '@testing-library/react'
import type {PageResult, ReviewDetail, ReviewTask} from '../types'

// ============================================================
// Unit Tests for ReviewPage (Task 12.2)
// ============================================================

describe('ReviewPage - Unit Tests', () => {
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

    /** Helper to create a mock review task. */
    function mockReviewTask(overrides: Partial<ReviewTask> = {}): ReviewTask {
        return {
            taskId: 1,
            requestId: 'req-001',
            userId: 'user-1',
            sourceType: 'MANUAL',
            contentPreview: 'Sample content preview text...',
            reviewStatus: 'CANDIDATE',
            createdAt: '2024-01-15T10:30:00',
            ...overrides,
        }
    }

    /** Helper to create a mock page result. */
    function mockPageResult(
        tasks: ReviewTask[],
        opts: { total?: number; current?: number; size?: number; pages?: number } = {},
    ): PageResult<ReviewTask> {
        return {
            records: tasks,
            total: opts.total ?? tasks.length,
            current: opts.current ?? 1,
            size: opts.size ?? 20,
            pages: opts.pages ?? 1,
        }
    }

    /** Helper to create a mock review detail. */
    function mockReviewDetail(overrides: Partial<ReviewDetail> = {}): ReviewDetail {
        return {
            taskId: 1,
            requestId: 'req-001',
            userId: 'user-1',
            sourceType: 'MANUAL',
            content: 'Full content of the review task for detailed inspection.',
            reviewStatus: 'CANDIDATE',
            createdAt: '2024-01-15T10:30:00',
            updatedAt: '2024-01-15T10:35:00',
            ...overrides,
        }
    }

    /** Set up fetch mock for review endpoints. */
    function setupFetchMock(
        pageResult: PageResult<ReviewTask>,
        opts: {
            detail?: ReviewDetail
            approveOk?: boolean
            rejectOk?: boolean
            batchOk?: boolean
        } = {},
    ) {
        const {detail, approveOk = true, rejectOk = true, batchOk = true} = opts

        globalThis.fetch = vi.fn().mockImplementation((url: string, init?: RequestInit) => {
            const method = init?.method ?? 'GET'

            // GET /api/v1/review/{taskId} — detail
            if (method === 'GET' && /\/api\/v1\/review\/\d+/.test(url)) {
                if (detail) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve(detail),
                    })
                }
                return Promise.resolve({
                    ok: false,
                    status: 404,
                    json: () => Promise.resolve({message: 'Not found'}),
                })
            }

            // GET /api/v1/review/pending — list
            if (method === 'GET' && url.includes('/api/v1/review/pending')) {
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () => Promise.resolve(pageResult),
                })
            }

            // POST /api/v1/review/approve
            if (method === 'POST' && url.includes('/api/v1/review/approve')) {
                if (approveOk) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve({message: '审核通过'}),
                    })
                }
                return Promise.resolve({
                    ok: false,
                    status: 500,
                    json: () => Promise.resolve({message: 'Approve failed'}),
                })
            }

            // POST /api/v1/review/reject
            if (method === 'POST' && url.includes('/api/v1/review/reject')) {
                if (rejectOk) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve({message: '审核拒绝'}),
                    })
                }
                return Promise.resolve({
                    ok: false,
                    status: 500,
                    json: () => Promise.resolve({message: 'Reject failed'}),
                })
            }

            // POST /api/v1/review/batch
            if (method === 'POST' && url.includes('/api/v1/review/batch')) {
                if (batchOk) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () =>
                            Promise.resolve({
                                results: [{taskId: 1, success: true, message: 'OK'}],
                            }),
                    })
                }
                return Promise.resolve({
                    ok: false,
                    status: 500,
                    json: () => Promise.resolve({message: 'Batch failed'}),
                })
            }

            return Promise.resolve({
                ok: false,
                status: 404,
                json: () => Promise.resolve({message: 'Not found'}),
            })
        })
    }

    async function renderReviewPage() {
        const {default: ReviewPage} = await import('./ReviewPage')
        return render(<ReviewPage/>)
    }

    // ---- Test: Table renders with mock review data ----

    it('should render table with mock review data', async () => {
        const tasks = [
            mockReviewTask({
                taskId: 1,
                requestId: 'req-001',
                userId: 'alice',
                sourceType: 'MANUAL'
            }),
            mockReviewTask({taskId: 2, requestId: 'req-002', userId: 'bob', sourceType: 'API'}),
            mockReviewTask({
                taskId: 3,
                requestId: 'req-003',
                userId: 'carol',
                sourceType: 'MANUAL'
            }),
        ]
        setupFetchMock(mockPageResult(tasks, {total: 3}))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('req-001')).toBeInTheDocument()
        }, {timeout: 10000})

        expect(screen.getByText('req-002')).toBeInTheDocument()
        expect(screen.getByText('req-003')).toBeInTheDocument()
        expect(screen.getByText('alice')).toBeInTheDocument()
        expect(screen.getByText('bob')).toBeInTheDocument()
        expect(screen.getByText('carol')).toBeInTheDocument()
        expect(screen.getAllByText('共 3 条任务').length).toBeGreaterThanOrEqual(1)
    })

    // ---- Test: Tab filtering switches between Pending/Approved/Rejected ----

    it('should switch tabs and re-fetch data', async () => {
        const tasks = [
            mockReviewTask({taskId: 1, reviewStatus: 'CANDIDATE'}),
        ]
        setupFetchMock(mockPageResult(tasks, {total: 1}))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('req-001')).toBeInTheDocument()
        }, {timeout: 10000})

        // Verify Pending tab is active by default
        expect(screen.getByText('待审核')).toBeInTheDocument()
        expect(screen.getByText('已通过')).toBeInTheDocument()
        expect(screen.getByText('已拒绝')).toBeInTheDocument()

        // Switch to Approved tab
        const approvedTab = screen.getByText('已通过')
        fireEvent.click(approvedTab)

        // The fetch should be called again (tab change triggers re-fetch)
        await waitFor(() => {
            const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
            // At least 2 calls: initial load + tab switch
            expect(calls.length).toBeGreaterThanOrEqual(2)
        })
    })

    it('should show empty state for tabs with no matching tasks', async () => {
        setupFetchMock(mockPageResult([], {total: 0}))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('暂无待审核任务')).toBeInTheDocument()
        }, {timeout: 10000})
    })

    // ---- Test: Approve flow calls correct API and refreshes ----

    it('should call approve API when approve button is clicked', async () => {
        const tasks = [
            mockReviewTask({taskId: 42, requestId: 'req-approve-test'}),
        ]
        setupFetchMock(mockPageResult(tasks, {total: 1}))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('req-approve-test')).toBeInTheDocument()
        }, {timeout: 10000})

        // Click the approve button (use getAllByTestId since fixed column may duplicate)
        const approveBtns = screen.getAllByTestId('approve-btn-42')
        fireEvent.click(approveBtns[0])

        // Verify the approve API was called
        await waitFor(() => {
            const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
            const approveCall = calls.find(
                (c) => (c[0] as string).includes('/api/v1/review/approve'),
            )
            expect(approveCall).toBeDefined()

            // Verify request body
            const body = JSON.parse(approveCall![1]?.body as string)
            expect(body.taskId).toBe(42)
            expect(body.reviewer).toBe('console')
        })
    })

    // ---- Test: Reject flow shows modal, submits reason, refreshes ----

    it('should show reject modal and submit rejection with reason', async () => {
        const tasks = [
            mockReviewTask({taskId: 99, requestId: 'req-reject-test'}),
        ]
        setupFetchMock(mockPageResult(tasks, {total: 1}))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('req-reject-test')).toBeInTheDocument()
        }, {timeout: 10000})

        // Click the reject button (use getAllByTestId since fixed column may duplicate)
        const rejectBtns = screen.getAllByTestId('reject-btn-99')
        fireEvent.click(rejectBtns[0])

        // Modal should appear
        await waitFor(() => {
            expect(screen.getByText('拒绝任务 #99')).toBeInTheDocument()
        })

        // Enter rejection reason
        const reasonInput = screen.getByTestId('reject-reason-input')
        fireEvent.change(reasonInput, {target: {value: 'Low quality content'}})

        // Click the OK (Reject) button in the modal footer
        // The modal has a footer with Cancel and OK (labeled "Reject") buttons
        const modalFooter = document.querySelector('.ant-modal-footer')
        expect(modalFooter).not.toBeNull()
        const submitBtn = within(modalFooter as HTMLElement).getByRole('button', {name: /拒.*绝/})
        fireEvent.click(submitBtn)

        // Verify the reject API was called
        await waitFor(() => {
            const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
            const rejectCall = calls.find(
                (c) => (c[0] as string).includes('/api/v1/review/reject'),
            )
            expect(rejectCall).toBeDefined()

            const body = JSON.parse(rejectCall![1]?.body as string)
            expect(body.taskId).toBe(99)
            expect(body.reviewer).toBe('console')
            expect(body.comment).toBe('Low quality content')
        })
    })

    // ---- Test: Batch selection and batch action ----

    it('should show batch action bar when tasks are selected', async () => {
        const tasks = [
            mockReviewTask({taskId: 10, requestId: 'req-batch-1'}),
            mockReviewTask({taskId: 11, requestId: 'req-batch-2'}),
        ]
        setupFetchMock(mockPageResult(tasks, {total: 2}))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('req-batch-1')).toBeInTheDocument()
        }, {timeout: 10000})

        // Select the first checkbox (row selection)
        const checkboxes = screen.getAllByRole('checkbox')
        // First checkbox is "select all", individual row checkboxes follow
        fireEvent.click(checkboxes[1]) // Select first row

        // Batch action bar should appear
        await waitFor(() => {
            expect(screen.getByTestId('batch-action-bar')).toBeInTheDocument()
            expect(screen.getByText('已选择 1 个任务')).toBeInTheDocument()
        })

        // Click batch approve
        const batchApproveBtn = screen.getByTestId('batch-approve-btn')
        fireEvent.click(batchApproveBtn)

        // Verify the batch API was called
        await waitFor(() => {
            const calls = (globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls
            const batchCall = calls.find(
                (c) => (c[0] as string).includes('/api/v1/review/batch'),
            )
            expect(batchCall).toBeDefined()

            const body = JSON.parse(batchCall![1]?.body as string)
            expect(body.reviewer).toBe('console')
            expect(body.items).toHaveLength(1)
            expect(body.items[0].action).toBe('APPROVE')
        })
    })

    // ---- Test: Row click opens detail panel ----

    it('should open detail drawer when a row is clicked', async () => {
        const task = mockReviewTask({
            taskId: 55,
            requestId: 'req-detail-test',
            userId: 'detail-user',
        })
        const detail = mockReviewDetail({
            taskId: 55,
            requestId: 'req-detail-test',
            userId: 'detail-user',
            content: 'Full detailed content for review.',
        })
        setupFetchMock(mockPageResult([task], {total: 1}), {detail})

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText('req-detail-test')).toBeInTheDocument()
        })

        // Click the row (on the request ID text)
        fireEvent.click(screen.getByText('req-detail-test'))

        // Drawer should appear with detail info
        await waitFor(() => {
            expect(
                screen.getByText('Full detailed content for review.'),
            ).toBeInTheDocument()
        })

        // Verify detail fields — "detail-user" appears in both table and drawer, use getAllByText
        const userElements = screen.getAllByText('detail-user')
        expect(userElements.length).toBeGreaterThanOrEqual(2) // table cell + drawer
    })

    // ---- Test: Error state with retry ----

    it('should show error state with retry button on fetch failure', async () => {
        globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Network error'))

        await renderReviewPage()

        await waitFor(() => {
            expect(screen.getByText(/Network error/)).toBeInTheDocument()
        })

        // Retry button should be present
        const retryBtn = screen.getByTestId('retry-button')
        expect(retryBtn).toBeInTheDocument()

        // Click retry — set up a successful response
        const tasks = [mockReviewTask({taskId: 1, requestId: 'req-after-retry'})]
        setupFetchMock(mockPageResult(tasks, {total: 1}))

        fireEvent.click(retryBtn)

        await waitFor(() => {
            expect(screen.getByText('req-after-retry')).toBeInTheDocument()
        })
    })

    it('permits unified candidate review only after processing reaches WAITING_REVIEW', async () => {
        const tasks = [
            mockReviewTask({taskId: 201, requestId: 'processing-unified', operation: 'PUBLISH', status: 'PROCESSING'}),
            mockReviewTask({taskId: 202, requestId: 'ready-unified', operation: 'PUBLISH', status: 'WAITING_REVIEW'}),
            mockReviewTask({taskId: 203, requestId: 'legacy-candidate', status: 'PROCESSING'}),
        ]
        setupFetchMock(mockPageResult(tasks))
        await renderReviewPage()
        await waitFor(() => expect(screen.getByText('processing-unified')).toBeInTheDocument())
        expect(screen.queryByTestId('approve-btn-201')).toBeNull()
        expect(screen.queryByTestId('reject-btn-201')).toBeNull()
        const processingRow = screen.getByText('processing-unified').closest('tr')!
        expect(within(processingRow).getByRole('checkbox')).toBeDisabled()
        expect(screen.getByTestId('approve-btn-202')).toBeEnabled()
        expect(screen.getByTestId('approve-btn-203')).toBeEnabled()
        fireEvent.click(screen.getByTestId('approve-btn-202'))
        await waitFor(() => expect((globalThis.fetch as ReturnType<typeof vi.fn>).mock.calls.some(
            call => String(call[0]).includes('/review/approve') && String(call[1]?.body).includes('202'))).toBe(true))
    })

})
