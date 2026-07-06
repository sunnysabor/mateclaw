// @vitest-environment happy-dom
import { describe, expect, it } from 'vitest'
import { parsePrompt, serializePrompt } from '../agentPromptProfile'

describe('agentPromptProfile identity card', () => {
  it('preserves a long identity card in Backstory when serializing and parsing', () => {
    const longIdentityCard = [
      '你是一名资深运营分析员工。',
      '工作原则：先定义问题，再拆指标，最后给出可执行动作。',
      '沟通风格：简洁、直接、给依据。',
      '边界：不编造数据，不确定时明确说明。',
    ].join('\n').repeat(800)

    const serialized = serializePrompt({
      role: '运营分析师',
      goal: '把业务问题拆成数据洞察和行动清单',
      backstory: longIdentityCard,
      extra: '',
    })
    const parsed = parsePrompt(serialized)

    expect(serialized).toContain('## Backstory')
    expect(parsed.backstory).toBe(longIdentityCard)
  })
})
