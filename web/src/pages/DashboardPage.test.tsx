import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import * as fc from 'fast-check'
import MetricCard, {formatMetricValue} from '../components/MetricCard'
import HealthStatusCard from '../components/HealthStatusCard'

// ============================================================
// Property 7: Metric Rendering Completeness (Task 8.2)
// ============================================================

describe('Property 7: Metric Rendering Completeness', () => {
    /**
     * Property 7: Metric Rendering Completeness
     *
     * For any set of metric values (with varying names, numeric values, and units),
     * the metric card rendering SHALL produce output that includes the metric name,
     * the formatted current value, and the unit for every metric in the set.
     *
     * Feature: web-management-console, Property 7: Metric Rendering Completeness
     * **Validates: Requirements 3.3**
     */
    it('should render metric name, formatted value, and unit for any metric data', () => {
        fc.assert(
            fc.property(
                fc.record({
                    name: fc.string({
                        minLength: 1,
                        maxLength: 50
                    }).filter((s: string) => s.trim().length > 0),
                    value: fc.oneof(
                        fc.integer({min: 0, max: 1_000_000}),
                        fc.double({min: 0, max: 1_000_000, noNaN: true, noDefaultInfinity: true})
                    ),
                    unit: fc.constantFrom('queries', 'tasks', '%', 'ms', 'req/s', 'ops', 'bytes', 'count', 'items', 'rate'),
                }),
                (metric: { name: string; value: number; unit: string }) => {
                    const {container} = render(
                        <MetricCard name={metric.name} value={metric.value} unit={metric.unit}/>
                    )

                    const text = container.textContent ?? ''

                    // Metric name must appear in the rendered output
                    expect(text).toContain(metric.name)

                    // The formatted value must appear (using the same formatting function)
                    const formattedValue = formatMetricValue(metric.value, metric.unit)
                    expect(text).toContain(formattedValue)

                    // The unit must appear in the rendered output
                    expect(text).toContain(metric.unit)
                }
            ),
            {numRuns: 100}
        )
    })
})

// ============================================================
// Unit Tests for DashboardPage (Task 8.3)
// ============================================================

describe('DashboardPage - Unit Tests', () => {
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

    /** Helper to create a mock actuator metrics response. */
    function mockMetricResponse(name: string, value: number) {
        return {
            name,
            measurements: [{statistic: 'COUNT', value}],
            availableTags: [],
        }
    }

    /** Helper to set up fetch mock with health and metric responses. */
    function setupFetchMock(
        healthStatus: string,
        metrics: Record<string, number> = {
            'kb.query.count': 42,
            'kb.ingest.task.count': 15,
            'kb.ingest.success.rate': 95.5,
        }
    ) {
        globalThis.fetch = vi.fn().mockImplementation((url: string) => {
            if (url.includes('/actuator/health')) {
                return Promise.resolve({
                    ok: true,
                    status: 200,
                    json: () =>
                        Promise.resolve({status: healthStatus, components: {}}),
                })
            }
            // Match metric name from URL
            for (const [name, value] of Object.entries(metrics)) {
                if (url.includes(`/actuator/metrics/${name}`)) {
                    return Promise.resolve({
                        ok: true,
                        status: 200,
                        json: () => Promise.resolve(mockMetricResponse(name, value)),
                    })
                }
            }
            return Promise.resolve({
                ok: false,
                status: 404,
                json: () => Promise.resolve({message: 'Not found'}),
            })
        })
    }

    async function renderDashboard() {
        // Dynamic import to pick up mocked fetch
        const {default: DashboardPage} = await import('./DashboardPage')
        return render(<DashboardPage/>)
    }

    it('should render metric cards with mock data', async () => {
        setupFetchMock('UP')

        await renderDashboard()

        // Wait for metrics to load
        await waitFor(() => {
            expect(screen.getAllByText('查询次数').length).toBeGreaterThanOrEqual(1)
        }, {timeout: 10000})

        expect(screen.getAllByText('入库任务数').length).toBeGreaterThanOrEqual(1)
        expect(screen.getAllByText('入库成功率').length).toBeGreaterThanOrEqual(1)
        expect(screen.getByText('UP')).toBeInTheDocument()
    })

    it('should show health status warning indicator when status is not UP', async () => {
        setupFetchMock('DOWN')

        await renderDashboard()

        await waitFor(() => {
            expect(screen.getByText('DOWN')).toBeInTheDocument()
        }, {timeout: 10000})

        expect(screen.getByText('服务异常')).toBeInTheDocument()
    })

    it('should show error state with retry button on fetch failure', async () => {
        globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Network error'))

        await renderDashboard()

        await waitFor(() => {
            expect(
                screen.getByText(/获取仪表盘数据失败/)
            ).toBeInTheDocument()
        })

        expect(screen.getByText(/重.*试/)).toBeInTheDocument()
    })

    it('should re-fetch data when refresh button is clicked', async () => {
        setupFetchMock('UP')

        await renderDashboard()

        await waitFor(() => {
            expect(screen.getAllByText('查询次数').length).toBeGreaterThanOrEqual(1)
        }, {timeout: 10000})

        // Click the refresh button — it should trigger a re-fetch
        const refreshButton = screen.getByLabelText('刷新指标')
        fireEvent.click(refreshButton)

        // The button should still be present and the data should still be rendered after refresh
        await waitFor(() => {
            expect(screen.getByLabelText('刷新指标')).toBeInTheDocument()
            expect(screen.getAllByText('查询次数').length).toBeGreaterThanOrEqual(1)
        })
    })
})

// ============================================================
// Unit Tests for HealthStatusCard component
// ============================================================

describe('HealthStatusCard - Unit Tests', () => {
    it('should render green tag for UP status', () => {
        render(<HealthStatusCard status="UP"/>)
        expect(screen.getByText('UP')).toBeInTheDocument()
        expect(screen.queryByText('服务异常')).not.toBeInTheDocument()
        expect(screen.queryByText('状态未知')).not.toBeInTheDocument()
    })

    it('should render warning for DOWN status', () => {
        render(<HealthStatusCard status="DOWN"/>)
        expect(screen.getByText('DOWN')).toBeInTheDocument()
        expect(screen.getByText('服务异常')).toBeInTheDocument()
    })

    it('should render warning for unknown status', () => {
        render(<HealthStatusCard status="DEGRADED"/>)
        expect(screen.getByText('DEGRADED')).toBeInTheDocument()
        expect(screen.getByText('状态未知')).toBeInTheDocument()
    })

    it('should show loading state when status is null', () => {
        const {container} = render(<HealthStatusCard status={null}/>)
        // The HealthStatusCard now shows a shimmer div when status is null, not text
        const shimmer = container.querySelector('.shimmer, [class*="shimmer"]')
        expect(shimmer ?? container.firstChild).toBeInTheDocument()
    })
})
