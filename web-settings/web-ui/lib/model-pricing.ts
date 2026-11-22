/**
 * 模型价格（每百万 token），与 Model.kt 的 ModelPricing 对齐；只用于统计页估算花费。
 */
export interface ModelPricing {
  input?: number | null;
  output?: number | null;
  cachedInput?: number | null;
}

export const PRICE_FIELDS = ["input", "cachedInput", "output"] as const;
export type PriceField = (typeof PRICE_FIELDS)[number];

/** 空串 = 未填；非法（负数、非数字、过大）返回 undefined */
export function parsePrice(text: string): number | null | undefined {
  const trimmed = text.trim();
  if (trimmed === "") return null;
  if (!/^\d*\.?\d*$/.test(trimmed)) return undefined;
  const value = Number(trimmed);
  if (!Number.isFinite(value) || value < 0 || value > 1_000_000) return undefined;
  return value;
}

export function formatPrice(value: number | null | undefined): string {
  if (value == null) return "";
  return String(Number(value.toPrecision(12)));
}

/** 把三个输入框的内容合成价格；全空为 null（不填价格），有非法项返回 undefined（不写回） */
export function pricingFromTexts(texts: Record<PriceField, string>): ModelPricing | null | undefined {
  const out: ModelPricing = {};
  let any = false;
  for (const field of PRICE_FIELDS) {
    const parsed = parsePrice(texts[field]);
    if (parsed === undefined) return undefined;
    if (parsed !== null) {
      out[field] = parsed;
      any = true;
    }
  }
  return any ? out : null;
}

export function pricingTexts(pricing: ModelPricing | null | undefined): Record<PriceField, string> {
  return {
    input: formatPrice(pricing?.input),
    cachedInput: formatPrice(pricing?.cachedInput),
    output: formatPrice(pricing?.output),
  };
}
