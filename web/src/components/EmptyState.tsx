import type {FC, ReactNode} from 'react'
import {InboxOutlined} from '@ant-design/icons'

export interface EmptyStateProps {
    /** Icon to display (defaults to InboxOutlined). */
    icon?: ReactNode
    /** Description text. */
    description: string
    /** Optional action button or element. */
    action?: ReactNode
}

/**
 * Shared empty state component with a refined dark-theme treatment.
 */
const EmptyState: FC<EmptyStateProps> = ({icon, description, action}) => {
    return (
        <div
            style={{
                display: 'flex',
                flexDirection: 'column',
                alignItems: 'center',
                justifyContent: 'center',
                padding: '48px 24px',
                gap: 16,
            }}
        >
            <div
                className="float"
                style={{
                    fontSize: 48,
                    color: '#3A3830',
                    lineHeight: 1,
                }}
            >
                {icon ?? <InboxOutlined/>}
            </div>
            <div
                style={{
                    fontSize: 14,
                    color: '#6B6860',
                    textAlign: 'center',
                    maxWidth: 280,
                    lineHeight: 1.6,
                }}
            >
                {description}
            </div>
            {action && <div style={{marginTop: 4}}>{action}</div>}
        </div>
    )
}

export default EmptyState
