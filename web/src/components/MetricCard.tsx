import {type CSSProperties, type FC} from 'react'

export interface MetricCardProps {
    /** Display name of the metric. */
    name: string
    /** Current numeric value. */
    value: number
    /** Unit label (e.g. "queries", "%", "tasks"). */
    unit: string
    /** Optional accent color for the indicator bar. */
    accent?: string
}

/**
 * Formats a metric value for display.
 * Percentage values get one decimal place; others use locale formatting.
 */
export function formatMetricValue(value: number, unit: string): string {
    if (unit === '%') return value.toFixed(1)
    return Number.isInteger(value) ? value.toLocaleString() : value.toFixed(2)
}

const cardStyle: CSSProperties = {
    position: 'relative',
    padding: '24px 24px 20px',
    borderRadius: 14,
    background: 'rgba(26, 29, 35, 0.7)',
    backdropFilter: 'blur(20px)',
    WebkitBackdropFilter: 'blur(20px)',
    border: '1px solid rgba(255, 255, 255, 0.05)',
    overflow: 'hidden',
    transition: 'all 0.3s cubic-bezier(0.22, 1, 0.36, 1)',
    cursor: 'default',
}

/**
 * A refined metric card with glass-morphism effect and accent indicator.
 */
const MetricCard: FC<MetricCardProps> = ({name, value, unit, accent = '#D4A853'}) => {
    const formattedValue = formatMetricValue(value, unit)

    return (
        <div
            className="glass-card"
            style={cardStyle}
            onMouseEnter={(e) => {
                e.currentTarget.style.borderColor = `${accent}22`
                e.currentTarget.style.transform = 'translateY(-3px)'
            }}
            onMouseLeave={(e) => {
                e.currentTarget.style.borderColor = 'rgba(255, 255, 255, 0.05)'
                e.currentTarget.style.transform = 'translateY(0)'
            }}
        >
            {/* Top accent bar */}
            <div
                style={{
                    position: 'absolute',
                    top: 0,
                    left: 0,
                    right: 0,
                    height: 2,
                    background: `linear-gradient(90deg, ${accent}, transparent)`,
                    opacity: 0.6,
                }}
            />

            {/* Label */}
            <div
                style={{
                    fontSize: 12,
                    fontWeight: 500,
                    color: '#8A8680',
                    textTransform: 'uppercase',
                    letterSpacing: '0.08em',
                    marginBottom: 12,
                }}
            >
                {name}
            </div>

            {/* Value */}
            <div style={{display: 'flex', alignItems: 'baseline', gap: 6}}>
        <span
            data-testid="metric-value"
            style={{
                fontFamily: '"JetBrains Mono", monospace',
                fontSize: 30,
                fontWeight: 500,
                color: '#E8E6E1',
                lineHeight: 1,
                letterSpacing: '-0.02em',
            }}
        >
          {formattedValue}
        </span>
                <span
                    style={{
                        fontSize: 13,
                        color: '#6B6860',
                        fontWeight: 500,
                    }}
                >
          {unit}
        </span>
            </div>
        </div>
    )
}

export default MetricCard
