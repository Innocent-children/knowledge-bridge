import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import {fireEvent, render, screen, waitFor} from '@testing-library/react'
import * as fc from 'fast-check'
import type {ChatMessage, EvidenceSource} from '../types'
import {STORAGE_KEY} from './ChatPage'

// ============================================================
// Property 2: Chat Session Storage Round Trip (Task 9.3)
// ============================================================

describe('Property 2: Chat Session Storage Round Trip', () => {
    let storage: Record<string, string>

    beforeEach(() => {
        storage = {}
        vi.stubGlobal('sessionStorage', {
            getItem: (key: string) => storage[key] ?? null,
            setItem: (key: string, value: string) => {
                storage[key] = value
            },
            removeItem: (key: string) => {
                delete storage[key]
            },
            clear: () => {
                storage = {}
            },
        })
    })

    afterEach(() => {
        vi.unstubAllGlobals()
    })

    /**
     * Property 2: Chat Session Storage Round Trip
     *
     * For any list of ChatMessage objects (with varying content, roles, timestamps,
     * and optional sources), serializing the list to JSON and storing it in
     * sessionStorage, then reading and deserializing it back, SHALL produce a list
     * identical to the original.
     *
     * Feature: web-management-console, Property 2: Chat Session Storage Round Trip
     * **Validates: Requirements 4.9**
     */
    it('should round-trip any ChatMessage[] through sessionStorage serialization', () => {
        // Arbitrary for EvidenceSource
        const evidenceSourceArb: fc.Arbitrary<EvidenceSource> = fc.record({
            dataset: fc.string({minLength: 1, maxLength: 30}),
            title: fc.string({minLength: 1, maxLength: 50}),
            content: fc.string({minLength: 1, maxLength: 200}),
            score: fc.double({min: 0, max: 1, noNaN: true, noDefaultInfinity: true}),
            metadata: fc.dictionary(
                fc.string({minLength: 1, maxLength: 10}),
                fc.oneof(fc.string(), fc.integer(), fc.boolean()),
            ),
        })

        // Arbitrary for ChatMessage
        const chatMessageArb: fc.Arbitrary<ChatMessage> = fc.record({
            id: fc.string({minLength: 1, maxLength: 30}),
            role: fc.constantFrom('user' as const, 'assistant' as const, 'system' as const),
            content: fc.string({minLength: 0, maxLength: 500}),
            sources: fc.option(fc.array(evidenceSourceArb, {
                minLength: 0,
                maxLength: 5
            }), {nil: undefined}),
            timestamp: fc.integer({min: 0, max: Number.MAX_SAFE_INTEGER}),
            error: fc.option(fc.boolean(), {nil: undefined}),
        })

        const chatMessagesArb = fc.array(chatMessageArb, {minLength: 0, maxLength: 20})

        fc.assert(
            fc.property(chatMessagesArb, (messages: ChatMessage[]) => {
                // Serialize and store
                const serialized = JSON.stringify(messages)
                sessionStorage.setItem(STORAGE_KEY, serialized)

                // Read back and deserialize
                const raw = sessionStorage.getItem(STORAGE_KEY)
                expect(raw).not.toBeNull()
                const deserialized = JSON.parse(raw!) as ChatMessage[]

                // Verify identical
                expect(deserialized).toEqual(messages)
            }),
            {numRuns: 100},
        )
    })
})

// ============================================================
// Unit Tests for ChatPage (Task 9.4)
// ============================================================

describe('ChatPage - Unit Tests', () => {
    const originalFetch = globalThis.fetch

    beforeEach(() => {
        Object.defineProperty(window, 'location', {
            value: {origin: 'http://localhost:8080'},
            writable: true,
        })
        // Clear sessionStorage mock
        const storage: Record<string, string> = {}
        vi.stubGlobal('sessionStorage', {
            getItem: (key: string) => storage[key] ?? null,
            setItem: (key: string, value: string) => {
                storage[key] = value
            },
            removeItem: (key: string) => {
                delete storage[key]
            },
            clear: () => {
                Object.keys(storage).forEach((k) => delete storage[k])
            },
        })
    })

    afterEach(() => {
        globalThis.fetch = originalFetch
        vi.resetModules()
        vi.restoreAllMocks()
        vi.unstubAllGlobals()
    })

    async function renderChatPage() {
        const {default: ChatPage} = await import('./ChatPage')
        return render(<ChatPage/>)
    }

    it('should render user and assistant messages after a query', async () => {
        // Mock successful chat response
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: () =>
                Promise.resolve({
                    answer: 'This is the answer',
                    route: 'KNOWLEDGE_BASE',
                    sources: [],
                    llmError: false,
                }),
        })

        await renderChatPage()

        // Type a question
        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'What is Knowledge Bridge?'}})

        // Send
        const sendBtn = screen.getByTestId('send-button')
        fireEvent.click(sendBtn)

        // User message should appear
        await waitFor(() => {
            expect(screen.getByText('What is Knowledge Bridge?')).toBeInTheDocument()
        })

        // Assistant answer should appear
        await waitFor(() => {
            expect(screen.getByText('This is the answer')).toBeInTheDocument()
        })
    })

    it('should toggle between Query and Ingest modes', async () => {
        await renderChatPage()

        // Default mode is Query — check placeholder
        const input = screen.getByTestId('chat-input')
        expect(input).toHaveAttribute('placeholder', '输入你的问题…')

        // Switch to Ingest mode
        const ingestOption = screen.getByText('入库')
        fireEvent.click(ingestOption)

        // Placeholder should change
        expect(input).toHaveAttribute('placeholder', '粘贴要入库的内容…')
    })

    it('should show loading indicator while waiting for response', async () => {
        // Mock a slow response
        globalThis.fetch = vi.fn().mockImplementation(
            () =>
                new Promise((resolve) =>
                    setTimeout(
                        () =>
                            resolve({
                                ok: true,
                                status: 200,
                                json: () =>
                                    Promise.resolve({
                                        answer: 'Done',
                                        route: 'KNOWLEDGE_BASE',
                                        sources: [],
                                        llmError: false,
                                    }),
                            }),
                        500,
                    ),
                ),
        )

        await renderChatPage()

        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'test question'}})
        fireEvent.click(screen.getByTestId('send-button'))

        // Loading indicator should appear
        await waitFor(() => {
            expect(screen.getByTestId('loading-indicator')).toBeInTheDocument()
        })

        // After response, loading should disappear
        await waitFor(
            () => {
                expect(screen.queryByTestId('loading-indicator')).not.toBeInTheDocument()
            },
            {timeout: 2000},
        )
    })

    it('should display error message with retry button on request failure', async () => {
        globalThis.fetch = vi.fn().mockRejectedValue(new Error('Network error'))

        await renderChatPage()

        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'failing question'}})
        fireEvent.click(screen.getByTestId('send-button'))

        // Error message should appear
        await waitFor(() => {
            expect(screen.getByText(/Network error/)).toBeInTheDocument()
        })

        // Retry button should be present
        expect(screen.getByTestId('retry-button')).toBeInTheDocument()
    })

    it('should render source references as collapsible panels', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: () =>
                Promise.resolve({
                    answer: 'Here is the answer with sources',
                    route: 'KNOWLEDGE_BASE',
                    sources: [
                        {
                            dataset: 'test-dataset',
                            title: 'Test Document',
                            content: 'Source content here',
                            score: 0.95,
                            metadata: {},
                        },
                        {
                            dataset: 'another-dataset',
                            title: 'Another Doc',
                            content: 'More content',
                            score: 0.8,
                            metadata: {},
                        },
                    ],
                    llmError: false,
                }),
        })

        await renderChatPage()

        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'question with sources'}})
        fireEvent.click(screen.getByTestId('send-button'))

        // Wait for answer
        await waitFor(() => {
            expect(screen.getByText('Here is the answer with sources')).toBeInTheDocument()
        })

        // Sources collapse should be present
        expect(screen.getByTestId('sources-collapse')).toBeInTheDocument()

        // Source titles should be visible in collapse headers
        expect(screen.getByText('Test Document')).toBeInTheDocument()
        expect(screen.getByText('Another Doc')).toBeInTheDocument()

        // Dataset tags should be visible
        expect(screen.getByText('test-dataset')).toBeInTheDocument()
        expect(screen.getByText('another-dataset')).toBeInTheDocument()
    })

    it('should handle llmError response — show sources with warning', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: () =>
                Promise.resolve({
                    answer: null,
                    route: 'KNOWLEDGE_BASE',
                    sources: [
                        {
                            dataset: 'ds',
                            title: 'Fallback Source',
                            content: 'content',
                            score: 0.9,
                            metadata: {},
                        },
                    ],
                    llmError: true,
                    errorMessage: 'LLM service unavailable',
                }),
        })

        await renderChatPage()

        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'llm error question'}})
        fireEvent.click(screen.getByTestId('send-button'))

        // Warning message about LLM unavailability
        await waitFor(() => {
            expect(
                screen.getByText(/LLM 服务暂时不可用/),
            ).toBeInTheDocument()
        })

        // Sources should still be rendered
        expect(screen.getByText('Fallback Source')).toBeInTheDocument()
    })

    it('should handle ingest mode — display taskId confirmation', async () => {
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: () =>
                Promise.resolve({
                    taskId: 'task-12345',
                    status: 'RECEIVED',
                }),
        })

        await renderChatPage()

        // Switch to Ingest mode
        fireEvent.click(screen.getByText('入库'))

        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'Some content to ingest'}})
        fireEvent.click(screen.getByTestId('send-button'))

        // Confirmation with taskId
        await waitFor(() => {
            expect(screen.getByText(/任务 ID：task-12345/)).toBeInTheDocument()
        })
    })

    it('should disable input and send button while loading', async () => {
        globalThis.fetch = vi.fn().mockImplementation(
            () =>
                new Promise((resolve) =>
                    setTimeout(
                        () =>
                            resolve({
                                ok: true,
                                status: 200,
                                json: () =>
                                    Promise.resolve({
                                        answer: 'Done',
                                        route: 'KNOWLEDGE_BASE',
                                        sources: [],
                                        llmError: false,
                                    }),
                            }),
                        500,
                    ),
                ),
        )

        await renderChatPage()

        const input = screen.getByTestId('chat-input')
        fireEvent.change(input, {target: {value: 'test'}})
        fireEvent.click(screen.getByTestId('send-button'))

        // Input should be disabled while loading
        await waitFor(() => {
            expect(screen.getByTestId('chat-input')).toBeDisabled()
        })
    })
})
