import type {FC} from 'react'
import {CheckCircleOutlined, CloseCircleOutlined, WarningOutlined,} from '@ant-design/icons'

export interface HealthStatusCardProps {
    /** Health status string from the actuator endpoint (e.g. "UP", "DOWN"). */
    status: string | null
}

const statusConfig: Record<string, {
    color: string;
    bg: string;
    icon: React.ReactNode;
    label: string
}> = {
    UP: {
        color: '#5EC269',
        bg: 'rgba(94, 194, 105, 0.08)',
        icon: <CheckCircleOutlined/>,
        label: '运行正常',
    },
    DOWN: {
        color: '#E05C5C',
        bg: 'rgba(224, 92, 92, 0.08)',
        icon: <CloseCircleOutlined/>,
        label: '服务异常',
    },
}

const defaultConfig = {
    color: '#E8B84B',
    bg: 'rgba(232, 184, 75, 0.08)',
    icon: <WarningOutlined/>,
    label: '状态未知',
}

/**
 * Displays the system health status with a refined glass-card treatment.
 */
const HealthStatusCard: FC<HealthStatusCardProps> = ({status}) => {
    const config = status ? (statusConfig[status] ?? defaultConfig) : defaultConfig
    const isUp = status === 'UP'

    return (
        <div
            className="glass-card"
            style={{
                position: 'relative',
                padding: '24px 24px 20px',
                borderRadius: 14,
                background: 'rgba(26, 29, 35, 0.7)',
                backdropFilter: 'blur(20px)',
                WebkitBackdropFilter: 'blur(20px)',
                border: '1px solid rgba(255, 255, 255, 0.05)',
                overflow: 'hidden',
                transition: 'all 0.3s cubic-bezier(0.22, 1, 0.36, 1)',
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
                    background: `linear-gradient(90deg, ${config.color}, transparent)`,
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
                系统健康状态
            </div>

            {/* Status indicator */}
            {status ? (
                <div style={{display: 'flex', alignItems: 'center', gap: 10}}>
                    <div
                        className={isUp ? 'pulse-glow' : ''}
                        style={{
                            width: 10,
                            height: 10,
                            borderRadius: '50%',
                            background: config.color,
                            boxShadow: `0 0 8px ${config.color}60`,
                            flexShrink: 0,
                        }}
                    />
                    <span
                        style={{
                            fontFamily: '"JetBrains Mono", monospace',
                            fontSize: 22,
                            fontWeight: 500,
                            color: config.color,
                            lineHeight: 1,
                        }}
                    >
            {status}
          </span>
                </div>
            ) : (
                <div className="shimmer" style={{height: 28, borderRadius: 6, width: 80}}/>
            )}

            {/* Sub-label */}
            {status && !isUp && (
                <div
                    role="alert"
                    aria-label="健康状态异常"
                    style={{
                        marginTop: 8,
                        fontSize: 12,
                        color: config.color,
                        fontWeight: 500,
                        display: 'flex',
                        alignItems: 'center',
                        gap: 6,
                    }}
                >
                    {config.icon}
                    {config.label}
                </div>
            )}
            {status && isUp && (
                <div
                    style={{
                        marginTop: 8,
                        fontSize: 12,
                        color: '#6B6860',
                        fontWeight: 500,
                    }}
                >
                    {config.label}
                </div>
            )}
        </div>
    )
}

export default HealthStatusCard
