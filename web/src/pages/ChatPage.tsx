import {type FC, type KeyboardEvent, useCallback, useEffect, useRef, useState} from 'react'
import {Button, Collapse, Input, Segmented, Spin, Tag,} from 'antd'
import {
    FileTextOutlined,
    InboxOutlined,
    ReloadOutlined,
    RobotOutlined,
    SendOutlined,
    ThunderboltOutlined,
    UserOutlined,
} from '@ant-design/icons'
import api from '../api/client'
import type {ChatMessage, ChatResponse, ChatState, EvidenceSource,} from '../types'

const {TextArea} = Input

const STORAGE_KEY = 'kb-chat-messages'

// ── sessionStorage helpers ──────────────────────────────────

function loadMessages(): ChatMessage[] {
    try {
        const raw = sessionStorage.getItem(STORAGE_KEY)
        if (!raw) return []
        return JSON.parse(raw) as ChatMessage[]
    } catch {
        return []
    }
}

function saveMessages(messages: ChatMessage[]): void {
    try {
        sessionStorage.setItem(STORAGE_KEY, JSON.stringify(messages))
    } catch {
        // sessionStorage unavailable — graceful degradation
    }
}

function generateId(): string {
    return `${Date.now()}-${Math.random().toString(36).slice(2, 9)}`
}

// ── component ───────────────────────────────────────────────

const ChatPage: FC = () => {
    const [state, setState] = useState<ChatState>(() => ({
        messages: loadMessages(),
        mode: 'query',
        loading: false,
    }))

    const messagesEndRef = useRef<HTMLDivElement>(null)
    const inputRef = useRef<HTMLTextAreaElement>(null)

    useEffect(() => {
        saveMessages(state.messages)
    }, [state.messages])

    useEffect(() => {
        messagesEndRef.current?.scrollIntoView({behavior: 'smooth'})
    }, [state.messages])

    const addMessage = useCallback(
        (msg: Omit<ChatMessage, 'id' | 'timestamp'>) => {
            const full: ChatMessage = {...msg, id: generateId(), timestamp: Date.now()}
            setState((prev) => ({...prev, messages: [...prev.messages, full]}))
            return full
        },
        [],
    )

    const handleSend = useCallback(
        async (text: string) => {
            const trimmed = text.trim()
            if (!trimmed || state.loading) return

            addMessage({role: 'user', content: trimmed})
            setState((prev) => ({...prev, loading: true}))

            if (state.mode === 'query') {
                try {
                    const res = await api.post<ChatResponse>('/api/v1/chat', {question: trimmed})

                    if (res.llmError) {
                        setState((prev) => ({
                            ...prev,
                            loading: false,
                            messages: [
                                ...prev.messages,
                                {
                                    id: generateId(),
                                    role: 'assistant' as const,
                                    content: '⚠️ LLM 服务暂时不可用，以下是检索到的相关来源：',
                                    sources: res.sources,
                                    timestamp: Date.now(),
                                },
                            ],
                        }))
                    } else {
                        setState((prev) => ({
                            ...prev,
                            loading: false,
                            messages: [
                                ...prev.messages,
                                {
                                    id: generateId(),
                                    role: 'assistant' as const,
                                    content: res.answer ?? '',
                                    sources: res.sources,
                                    timestamp: Date.now(),
                                },
                            ],
                        }))
                    }
                } catch (err) {
                    const errorMsg = err instanceof Error ? err.message : '请求失败'
                    setState((prev) => ({
                        ...prev,
                        loading: false,
                        messages: [
                            ...prev.messages,
                            {
                                id: generateId(),
                                role: 'assistant' as const,
                                content: errorMsg,
                                timestamp: Date.now(),
                                error: true,
                            },
                        ],
                    }))
                }
            } else {
                try {
                    const res = await api.post<{ taskId: string; status: string }>(
                        '/api/v1/ingest/manual',
                        {
                            requestId: `console-${Date.now()}`,
                            userId: 'console',
                            content: trimmed,
                            sourceType: 'MARKDOWN',
                            force: false,
                        },
                    )
                    setState((prev) => ({
                        ...prev,
                        loading: false,
                        messages: [
                            ...prev.messages,
                            {
                                id: generateId(),
                                role: 'assistant' as const,
                                content: `内容已提交入库，任务 ID：${res.taskId}`,
                                timestamp: Date.now(),
                            },
                        ],
                    }))
                } catch (err) {
                    const errorMsg = err instanceof Error ? err.message : '入库请求失败'
                    setState((prev) => ({
                        ...prev,
                        loading: false,
                        messages: [
                            ...prev.messages,
                            {
                                id: generateId(),
                                role: 'assistant' as const,
                                content: errorMsg,
                                timestamp: Date.now(),
                                error: true,
                            },
                        ],
                    }))
                }
            }
        },
        [state.loading, state.mode, addMessage],
    )

    const handleRetry = useCallback(
        (messageIndex: number) => {
            for (let i = messageIndex - 1; i >= 0; i--) {
                if (state.messages[i].role === 'user') {
                    setState((prev) => ({
                        ...prev,
                        messages: prev.messages.filter((_, idx) => idx !== messageIndex),
                    }))
                    handleSend(state.messages[i].content)
                    return
                }
            }
        },
        [state.messages, handleSend],
    )

    const [inputValue, setInputValue] = useState('')

    const onSubmit = () => {
        if (inputValue.trim()) {
            handleSend(inputValue)
            setInputValue('')
        }
    }

    const onKeyDown = (e: KeyboardEvent<HTMLTextAreaElement>) => {
        if (e.key === 'Enter' && !e.shiftKey) {
            e.preventDefault()
            onSubmit()
        }
    }

    return (
        <div
            style={{
                display: 'flex',
                flexDirection: 'column',
                height: 'calc(100vh - 56px)',
                maxWidth: 900,
                margin: '0 auto',
                padding: '0 24px',
            }}
        >
            {/* Header */}
            <div
                style={{
                    display: 'flex',
                    justifyContent: 'space-between',
                    alignItems: 'center',
                    padding: '20px 0 16px',
                    flexShrink: 0,
                }}
            >
                <div style={{display: 'flex', alignItems: 'center', gap: 12}}>
                    <div
                        style={{
                            width: 36,
                            height: 36,
                            borderRadius: 10,
                            background: 'linear-gradient(135deg, rgba(212, 168, 83, 0.15), rgba(212, 168, 83, 0.05))',
                            border: '1px solid rgba(212, 168, 83, 0.15)',
                            display: 'flex',
                            alignItems: 'center',
                            justifyContent: 'center',
                            color: '#D4A853',
                            fontSize: 16,
                        }}
                    >
                        <ThunderboltOutlined/>
                    </div>
                    <div>
                        <div
                            style={{
                                fontFamily: '"Playfair Display", serif',
                                fontSize: 18,
                                fontWeight: 700,
                                color: '#E8E6E1',
                                letterSpacing: '-0.02em',
                                lineHeight: 1.2,
                            }}
                        >
                            对话
                        </div>
                        <div style={{fontSize: 12, color: '#6B6860'}}>
                            {state.mode === 'query' ? '知识库问答' : '内容入库'}
                        </div>
                    </div>
                </div>
                <Segmented
                    value={state.mode}
                    onChange={(val) =>
                        setState((prev) => ({...prev, mode: val as 'query' | 'ingest'}))
                    }
                    options={[
                        {label: '问答', value: 'query', icon: <RobotOutlined/>},
                        {label: '入库', value: 'ingest', icon: <InboxOutlined/>},
                    ]}
                />
            </div>

            <div className="accent-line" style={{flexShrink: 0}}/>

            {/* Message list */}
            <div
                data-testid="message-list"
                style={{
                    flex: 1,
                    overflowY: 'auto',
                    padding: '20px 0',
                    display: 'flex',
                    flexDirection: 'column',
                    gap: 16,
                }}
            >
                {state.messages.length === 0 && (
                    <div
                        style={{
                            display: 'flex',
                            flexDirection: 'column',
                            alignItems: 'center',
                            justifyContent: 'center',
                            flex: 1,
                            gap: 16,
                            padding: '60px 0',
                        }}
                    >
                        <div
                            className="float"
                            style={{
                                width: 64,
                                height: 64,
                                borderRadius: 18,
                                background: 'linear-gradient(135deg, rgba(212, 168, 83, 0.1), rgba(107, 163, 190, 0.08))',
                                border: '1px solid rgba(255, 255, 255, 0.04)',
                                display: 'flex',
                                alignItems: 'center',
                                justifyContent: 'center',
                                fontSize: 28,
                                color: '#4A473F',
                            }}
                        >
                            <RobotOutlined/>
                        </div>
                        <div style={{textAlign: 'center'}}>
                            <div style={{fontSize: 15, color: '#6B6860', fontWeight: 500}}>
                                {state.mode === 'query' ? '输入问题开始对话' : '粘贴内容以入库到知识库'}
                            </div>
                            <div style={{fontSize: 12, color: '#4A473F', marginTop: 6}}>
                                {state.mode === 'query'
                                    ? '支持自然语言查询，系统将从知识库中检索相关内容'
                                    : '支持 Markdown 格式，内容将经过审核后入库'}
                            </div>
                        </div>
                    </div>
                )}

                {state.messages.map((msg, idx) => (
                    <MessageBubble
                        key={msg.id}
                        message={msg}
                        onRetry={msg.error ? () => handleRetry(idx) : undefined}
                    />
                ))}

                {state.loading && (
                    <div
                        data-testid="loading-indicator"
                        style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 10,
                            padding: '12px 16px',
                        }}
                    >
                        <Spin size="small"/>
                        <span style={{fontSize: 13, color: '#6B6860'}}>思考中…</span>
                    </div>
                )}

                <div ref={messagesEndRef}/>
            </div>

            {/* Input area */}
            <div
                style={{
                    flexShrink: 0,
                    padding: '16px 0 20px',
                    borderTop: '1px solid rgba(255, 255, 255, 0.04)',
                }}
            >
                <div
                    style={{
                        display: 'flex',
                        gap: 10,
                        alignItems: 'flex-end',
                        background: '#1A1D23',
                        borderRadius: 14,
                        border: '1px solid #2E3240',
                        padding: '10px 12px',
                        transition: 'border-color 0.2s ease, box-shadow 0.2s ease',
                    }}
                    onFocus={(e) => {
                        e.currentTarget.style.borderColor = 'rgba(212, 168, 83, 0.3)'
                        e.currentTarget.style.boxShadow = '0 0 0 3px rgba(212, 168, 83, 0.06)'
                    }}
                    onBlur={(e) => {
                        if (!e.currentTarget.contains(e.relatedTarget)) {
                            e.currentTarget.style.borderColor = '#2E3240'
                            e.currentTarget.style.boxShadow = 'none'
                        }
                    }}
                >
                    <TextArea
                        ref={inputRef as React.Ref<any>}
                        value={inputValue}
                        onChange={(e) => setInputValue(e.target.value)}
                        onKeyDown={onKeyDown}
                        placeholder={
                            state.mode === 'query' ? '输入你的问题…' : '粘贴要入库的内容…'
                        }
                        autoSize={{minRows: 1, maxRows: 5}}
                        disabled={state.loading}
                        variant="borderless"
                        style={{
                            flex: 1,
                            background: 'transparent',
                            color: '#E8E6E1',
                            fontSize: 14,
                            resize: 'none',
                            padding: '4px 0',
                        }}
                        data-testid="chat-input"
                    />
                    <Button
                        type="primary"
                        icon={<SendOutlined/>}
                        onClick={onSubmit}
                        disabled={state.loading || !inputValue.trim()}
                        aria-label="发送消息"
                        data-testid="send-button"
                        style={{
                            borderRadius: 10,
                            width: 40,
                            height: 40,
                            flexShrink: 0,
                        }}
                    />
                </div>
                <div style={{fontSize: 11, color: '#4A473F', marginTop: 8, textAlign: 'center'}}>
                    Enter 发送 · Shift + Enter 换行
                </div>
            </div>
        </div>
    )
}

// ── MessageBubble ───────────────────────────────────────────

interface MessageBubbleProps {
    message: ChatMessage
    onRetry?: () => void
}

const MessageBubble: FC<MessageBubbleProps> = ({message, onRetry}) => {
    const isUser = message.role === 'user'

    return (
        <div
            style={{
                display: 'flex',
                justifyContent: isUser ? 'flex-end' : 'flex-start',
                gap: 10,
            }}
            className="fade-in"
        >
            {/* Avatar for assistant */}
            {!isUser && (
                <div
                    style={{
                        width: 32,
                        height: 32,
                        borderRadius: 9,
                        background: 'linear-gradient(135deg, rgba(212, 168, 83, 0.12), rgba(107, 163, 190, 0.08))',
                        border: '1px solid rgba(255, 255, 255, 0.05)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 14,
                        color: '#D4A853',
                        flexShrink: 0,
                        marginTop: 2,
                    }}
                >
                    <RobotOutlined/>
                </div>
            )}

            <div style={{maxWidth: '72%', display: 'flex', flexDirection: 'column', gap: 6}}>
                {/* Sender label */}
                <div
                    style={{
                        fontSize: 11,
                        color: '#6B6860',
                        fontWeight: 500,
                        textAlign: isUser ? 'right' : 'left',
                        letterSpacing: '0.03em',
                    }}
                >
                    {isUser ? '我' : 'Knowledge Bridge'}
                </div>

                {/* Bubble */}
                <div
                    data-testid={isUser ? 'user-message' : 'assistant-message'}
                    style={{
                        padding: '12px 16px',
                        borderRadius: isUser ? '14px 14px 4px 14px' : '14px 14px 14px 4px',
                        background: isUser
                            ? 'linear-gradient(135deg, #D4A853, #C49A45)'
                            : '#1E2128',
                        color: isUser ? '#12141A' : '#C8C4BC',
                        border: isUser ? 'none' : '1px solid rgba(255, 255, 255, 0.04)',
                        fontSize: 14,
                        lineHeight: 1.65,
                        boxShadow: isUser
                            ? '0 2px 12px rgba(212, 168, 83, 0.2)'
                            : '0 2px 8px rgba(0, 0, 0, 0.15)',
                    }}
                >
                    {message.error ? (
                        <div style={{
                            display: 'flex',
                            alignItems: 'center',
                            gap: 8,
                            flexWrap: 'wrap'
                        }}>
                            <span style={{color: '#E05C5C'}}>⚠ {message.content}</span>
                            {onRetry && (
                                <Button
                                    size="small"
                                    icon={<ReloadOutlined/>}
                                    onClick={onRetry}
                                    data-testid="retry-button"
                                    style={{
                                        borderColor: '#2E3240',
                                        color: '#9B978F',
                                        background: 'rgba(255, 255, 255, 0.04)',
                                        fontSize: 12,
                                    }}
                                >
                                    重试
                                </Button>
                            )}
                        </div>
                    ) : (
                        <div style={{whiteSpace: 'pre-wrap', wordBreak: 'break-word'}}>
                            {message.content}
                        </div>
                    )}
                </div>

                {/* Evidence sources */}
                {message.sources && message.sources.length > 0 && (
                    <SourcesPanel sources={message.sources}/>
                )}
            </div>

            {/* Avatar for user */}
            {isUser && (
                <div
                    style={{
                        width: 32,
                        height: 32,
                        borderRadius: 9,
                        background: 'linear-gradient(135deg, #D4A853, #C49A45)',
                        display: 'flex',
                        alignItems: 'center',
                        justifyContent: 'center',
                        fontSize: 14,
                        color: '#12141A',
                        flexShrink: 0,
                        marginTop: 2,
                        fontWeight: 600,
                    }}
                >
                    <UserOutlined/>
                </div>
            )}
        </div>
    )
}

// ── SourcesPanel ────────────────────────────────────────────

interface SourcesPanelProps {
    sources: EvidenceSource[]
}

const SourcesPanel: FC<SourcesPanelProps> = ({sources}) => {
    const items = sources.map((src, idx) => ({
        key: String(idx),
        label: (
            <span style={{display: 'flex', alignItems: 'center', gap: 8, fontSize: 13}}>
        <FileTextOutlined style={{color: '#D4A853'}}/>
        <span style={{color: '#C8C4BC'}}>{src.title || `来源 ${idx + 1}`}</span>
        <Tag
            style={{
                background: 'rgba(212, 168, 83, 0.1)',
                border: '1px solid rgba(212, 168, 83, 0.15)',
                color: '#D4A853',
                fontSize: 11,
            }}
        >
          {src.dataset}
        </Tag>
        <Tag
            style={{
                background: 'rgba(107, 163, 190, 0.08)',
                border: '1px solid rgba(107, 163, 190, 0.12)',
                color: '#6BA3BE',
                fontSize: 11,
            }}
        >
          {(src.score * 100).toFixed(0)}%
        </Tag>
      </span>
        ),
        children: (
            <div
                style={{
                    whiteSpace: 'pre-wrap',
                    fontSize: 13,
                    color: '#9B978F',
                    lineHeight: 1.7,
                }}
            >
                {src.content}
            </div>
        ),
    }))

    return (
        <Collapse
            size="small"
            items={items}
            style={{
                borderRadius: 10,
                fontSize: 13,
                border: '1px solid rgba(255, 255, 255, 0.04)',
            }}
            data-testid="sources-collapse"
        />
    )
}

export default ChatPage

// Export helpers for testing
export {loadMessages, saveMessages, STORAGE_KEY}
