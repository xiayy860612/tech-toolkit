import { describe, it, expect } from '@jest/globals'
import { cn } from '@/lib/utils'

describe('cn utility function', () => {
  it('should merge class names correctly', () => {
    expect(cn('foo', 'bar')).toBe('foo bar')
  })

  it('should handle conditional classes', () => {
    expect(cn('foo', false && 'bar', 'baz')).toBe('foo baz')
  })

  it('should handle undefined and null values', () => {
    expect(cn('foo', undefined, null, 'bar')).toBe('foo bar')
  })

  it('should handle empty strings', () => {
    expect(cn('foo', '', 'bar')).toBe('foo bar')
  })

  it('should handle arrays of classes', () => {
    expect(cn(['foo', 'bar'], 'baz')).toBe('foo bar baz')
  })

  it('should handle objects with boolean values', () => {
    expect(cn({ foo: true, bar: false, baz: true })).toBe('foo baz')
  })

  it('should merge conflicting tailwind classes correctly', () => {
    // Later classes should override earlier ones for Tailwind conflicts
    expect(cn('p-4', 'p-2')).toBe('p-2')
  })

  it('should handle complex inputs', () => {
    expect(cn(
      'base-class',
      ['array-class-1', 'array-class-2'],
      { conditional: true, 'conditional-false': false },
      null,
      undefined,
      'final-class'
    )).toBe('base-class array-class-1 array-class-2 conditional final-class')
  })

  it('should handle no arguments', () => {
    expect(cn()).toBe('')
  })

  it('should handle single argument', () => {
    expect(cn('single-class')).toBe('single-class')
  })

  it('should handle only false values', () => {
    expect(cn(false, null, undefined, '')).toBe('')
  })

  it('should properly merge classes with similar but different tailwind utilities', () => {
    // Should keep both when they're for different properties
    expect(cn('text-red-500', 'bg-blue-500')).toBe('text-red-500 bg-blue-500')
  })
})
