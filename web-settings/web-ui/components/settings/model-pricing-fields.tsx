import * as React from "react";

import { useTranslation } from "react-i18next";

import { Button } from "~/components/ui/button";
import { Input } from "~/components/ui/input";
import {
  PRICE_FIELDS,
  formatPrice,
  pricingFromTexts,
  pricingTexts,
  type ModelPricing,
  type PriceField,
} from "@overlay/lib/model-pricing";

/** 模型价格三项（每百万 token）+ models.dev 参考价一键填入 */
export function ModelPricingFields({
  modelKey,
  pricing,
  referencePrice,
  onChange,
  onValidityChange,
}: {
  /** 切换到另一个模型时重置输入框 */
  modelKey: string;
  pricing: ModelPricing | null | undefined;
  referencePrice: ModelPricing | null | undefined;
  onChange: (pricing: ModelPricing | null) => void;
  onValidityChange?: (valid: boolean) => void;
}) {
  const { t } = useTranslation("settings");
  const [texts, setTexts] = React.useState(() => pricingTexts(pricing));
  // 只在换模型时从外部同步，输入过程中保留原文（允许「1.」这样的中间状态）
  React.useEffect(() => setTexts(pricingTexts(pricing)), [modelKey]); // eslint-disable-line react-hooks/exhaustive-deps
  React.useEffect(() => {
    onValidityChange?.(pricingFromTexts(texts) !== undefined);
  }, [texts, onValidityChange]);

  const update = (field: PriceField, value: string) => {
    const next = { ...texts, [field]: value.slice(0, 32) };
    setTexts(next);
    const parsed = pricingFromTexts(next);
    if (parsed !== undefined) onChange(parsed);
  };

  return (
    <div className="space-y-2" data-testid="model-pricing">
      <div>
        <p className="text-sm font-medium">{t("providers.fields.pricing")}</p>
        <p className="text-xs text-muted-foreground">{t("providers.hints.pricing")}</p>
      </div>
      <div className="grid grid-cols-3 gap-2">
        {PRICE_FIELDS.map((field) => (
          <label key={field} className="space-y-1 text-xs text-muted-foreground">
            <span>{t(`providers.fields.pricing_${field}`)}</span>
            <Input
              inputMode="decimal"
              value={texts[field]}
              aria-invalid={texts[field] !== "" && pricingFromTexts({ ...texts }) === undefined}
              onChange={(event) => update(field, event.target.value)}
              data-testid={`model-pricing-${field}`}
            />
          </label>
        ))}
      </div>
      {pricingFromTexts(texts) === undefined ? (
        <p className="text-xs text-destructive" role="alert">{t("providers.hints.pricing_invalid")}</p>
      ) : null}
      {referencePrice ? (
        <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
          <span>
            {t("providers.hints.pricing_reference", {
              input: formatPrice(referencePrice.input),
              output: formatPrice(referencePrice.output),
              cached: referencePrice.cachedInput == null ? "—" : formatPrice(referencePrice.cachedInput),
            })}
          </span>
          <Button
            type="button"
            size="sm"
            variant="outline"
            onClick={() => {
              setTexts(pricingTexts(referencePrice));
              onChange({ ...referencePrice });
            }}
          >
            {t("providers.actions.fill_reference_price")}
          </Button>
        </div>
      ) : null}
    </div>
  );
}
