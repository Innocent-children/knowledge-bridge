import {afterEach, beforeEach, describe, expect, it, vi} from 'vitest'
import * as fc from 'fast-check'
import {ApiClientError} from './client'

// We need to test the client module, but it uses window.location.origin
// So we mock fetch globally and import the module dynamically

describe('API Client - Property 6: API Client Error Handling', () => {
    const originalFetch = globalThis.fetch

    beforeEach(() => {
        // Mock window.location.origin
        Object.defineProperty(window, 'location', {
            value: {origin: 'http://localhost:8080'},
            writable: true,
        })
    })

    afterEach(() => {
        globalThis.fetch = originalFetch
        vi.resetModules()
    })

    /**
     * Property 6: API Client Error Handling
     *
     * For any HTTP response with a status code in the range 400–599,
     * the API client SHALL throw a structured error object containing
     * the numeric status code and a descriptive message,
     * and SHALL never return a resolved promise.
     *
     * Feature: web-management-console, Property 6: API Client Error Handling
     * Validates: Requirements 10.3
     */
    it('should throw ApiClientError for any HTTP error status (400-599)', async () => {
        await fc.assert(
            fc.asyncProperty(
                fc.integer({min: 400, max: 599}),
                fc.string({minLength: 1, maxLength: 100}),
                async (statusCode, errorMessage) => {
                    // Mock fetch to return the generated error status
                    globalThis.fetch = vi.fn().mockResolvedValue({
                        ok: false,
                        status: statusCode,
                        json: () => Promise.resolve({message: errorMessage}),
                    })

                    // Dynamically import to get fresh module with mocked fetch
                    const {api} = await import('./client')

                    // The API client should reject with a structured error
                    try {
                        await api.get('/api/v1/test')
                        // If we reach here, the promise resolved — this is a failure
                        expect.fail('API client should not resolve for error status codes')
                    } catch (error) {
                        // Verify it's a structured error with status code and message
                        expect(error).toBeInstanceOf(ApiClientError)
                        const apiError = error as ApiClientError
                        expect(apiError.status).toBe(statusCode)
                        expect(typeof apiError.message).toBe('string')
                        expect(apiError.message.length).toBeGreaterThan(0)
                    }
                }
            ),
            {numRuns: 100}
        )
    })

    it('should never return a resolved promise for error status codes', async () => {
        await fc.assert(
            fc.asyncProperty(
                fc.integer({min: 400, max: 599}),
                async (statusCode) => {
                    globalThis.fetch = vi.fn().mockResolvedValue({
                        ok: false,
                        status: statusCode,
                        json: () => Promise.reject(new Error('no body')),
                    })

                    const {api} = await import('./client')

                    let resolved = false
                    try {
                        await api.post('/api/v1/test', {data: 'test'})
                        resolved = true
                    } catch {
                        // Expected path
                    }

                    expect(resolved).toBe(false)
                }
            ),
            {numRuns: 100}
        )
    })
})

describe('API Client - Unit Tests', () => {
    afterEach(() => {
        vi.resetModules()
        vi.restoreAllMocks()
    })

    beforeEach(() => {
        Object.defineProperty(window, 'location', {
            value: {origin: 'http://localhost:8080'},
            writable: true,
        })
    })

    it('should parse JSON response on successful GET', async () => {
        const mockData = {id: 1, name: 'test'}
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: () => Promise.resolve(mockData),
        })

        const {api} = await import('./client')
        const result = await api.get('/api/v1/test')
        expect(result).toEqual(mockData)
    })

    it('should parse JSON response on successful POST', async () => {
        const mockData = {success: true}
        globalThis.fetch = vi.fn().mockResolvedValue({
            ok: true,
            status: 200,
            json: () => Promise.resolve(mockData),
        })

        const {api} = await import('./client')
        const result = await api.post('/api/v1/test', {question: 'hello'})
        expect(result).toEqual(mockData)
    })

    it('should throw ApiClientError on network failure', async () => {
        globalThis.fetch = vi.fn().mockRejectedValue(new TypeError('Failed to fetch'))

        const {api, ApiClientError} = await import('./client')

        await expect(api.get('/api/v1/test')).rejects.toBeInstanceOf(ApiClientError)
    })
})
