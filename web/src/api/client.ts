import type { ApiError } from '../types'

const BASE_URL = window.location.origin
const DEFAULT_TIMEOUT = 30_000
const CHAT_TIMEOUT = 180_000

class ApiClientError extends Error implements ApiError {
    status: number

    constructor(status: number, message: string) {
        super(message)
        this.status = status
        this.name = 'ApiClientError'
    }
}

async function request<T>(
    url: string,
    options: RequestInit & { timeout?: number } = {}
): Promise<T> {
    const timeout = options.timeout ?? (url.includes('/api/v1/chat') ? CHAT_TIMEOUT : DEFAULT_TIMEOUT)

    const controller = new AbortController()
    const timeoutId = setTimeout(() => controller.abort(), timeout)

    try {
        const response = await fetch(`${BASE_URL}${url}`, {
            ...options,
            signal: controller.signal,
            headers: {
                'Content-Type': 'application/json',
                ...options.headers,
            },
        })

        if (!response.ok) {
            let message = `HTTP ${response.status}`
            try {
                const errorBody = await response.json()
                message = errorBody.message || errorBody.error || message
            } catch {
                // Use default message if body parsing fails
            }
            throw new ApiClientError(response.status, message)
        }

        return await response.json() as T
    } catch (error) {
        if (error instanceof ApiClientError) {
            throw error
        }
        if (error instanceof DOMException && error.name === 'AbortError') {
            throw new ApiClientError(408, 'Request timeout')
        }
        throw new ApiClientError(0, error instanceof Error ? error.message : 'Network error')
    } finally {
        clearTimeout(timeoutId)
    }
}

export const api = {
    get: <T>(url: string) => request<T>(url, { method: 'GET' }),
    post: <T>(url: string, body: unknown) =>
        request<T>(url, { method: 'POST', body: JSON.stringify(body) }),
}

export { ApiClientError }
export default api
