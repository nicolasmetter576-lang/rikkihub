/**
 * 去八股：设置读写、试一试、聊天里的改动清单操作，以及读消息上改动记录的纯函数。
 * 规则、正则、AI 精修都在服务端（与 App 共用 Kotlin 代码）。
 * @see web-settings/src/main/java/me/rerere/rikkihub/web/routes/QubaguRoutes.kt
 */

import api from "~/services/api";

export type QubaguMode = "AUTO" | "REVIEW";

export const QUBAGU_MODES: QubaguMode[] = ["AUTO", "REVIEW"];

interface QubaguRuleBase {
  id: string;
  name: string;
  enabled: boolean;
  /** 这条规则命中的句子交给 AI 精修时一起发给模型的要求；空 = 不发。 */
  refinePrompt: string;
}

/** 查找替换。`replacements` 为空 = 删掉。 */
export interface QubaguReplaceRule extends QubaguRuleBase {
  kind: "replace";
  sample: string;
  find: string;
  regex: boolean;
  replacements: string[];
  dialogue: boolean;
  keepFirst: boolean;
  atEnd: boolean;
}

/** 限量：每个词在一段旁白里留前 `max` 次。 */
export interface QubaguQuotaRule extends QubaguRuleBase {
  kind: "quota";
  words: string[];
  max: number;
}

export type QubaguRule = QubaguReplaceRule | QubaguQuotaRule;

export interface QubaguRulePack {
  id: string;
  name: string;
  description: string;
  enabled: boolean;
  rules: QubaguRule[];
}

export interface QubaguConfig {
  /** 聊天回复的总开关（所有助手、所有会话）。 */
  enabled: boolean;
  mode: QubaguMode;
  protectDialogue: boolean;
  protectCornerQuotes: boolean;
  protectDetails: boolean;
  packs: QubaguRulePack[];
  merge: boolean;
  mergeMaxChars: number;
  refine: boolean;
  refineModelId: string | null;
  /** null = 内置的精修要求。 */
  refinePrompt: string | null;
}

export type QubaguConfigPatch = Partial<QubaguConfig>;

export interface QubaguConfigResponse {
  config: QubaguConfig;
  defaults: { refinePrompt: string };
}

export const QUBAGU_LIMITS = {
  mergeMaxChars: { min: 2, max: 20 },
  quotaMax: { min: 0, max: 10 },
  packs: 50,
  rules: 500,
  nameChars: 100,
  sampleChars: 300,
  descriptionChars: 500,
  findChars: 1500,
  replacements: 24,
  words: 60,
  ruleRefinePromptChars: 1000,
  refinePromptChars: 4000,
} as const;

export function fetchQubaguConfig(): Promise<QubaguConfigResponse> {
  return api.get<QubaguConfigResponse>("qubagu/config");
}

export function updateQubaguConfig(patch: QubaguConfigPatch): Promise<QubaguConfigResponse> {
  return api.put<QubaguConfigResponse>("qubagu/config", patch);
}

export type QubaguImportChoice = "OVERWRITE" | "COPY" | "SKIP";
export const QUBAGU_IMPORT_CHOICES: QubaguImportChoice[] = ["OVERWRITE", "COPY", "SKIP"];

export interface QubaguImportResult {
  packs: Array<{ id: string; name: string; rules: number; conflict: boolean }>;
  /** 正则写错、导入时被关掉的规则（「包名 › 规则名」）。 */
  invalid: string[];
  /** 保存后才有。 */
  config?: QubaguConfigResponse;
}

/** `preview` 只解析、看哪些包和已有的冲突；否则按 `choices` 保存（冲突的包缺省覆盖）。 */
export function importQubaguRules(
  text: string,
  options: { preview: true } | { preview?: false; choices: Record<string, QubaguImportChoice> },
): Promise<QubaguImportResult> {
  return api.post<QubaguImportResult>("qubagu/import", { text, ...options });
}

/** 规则文件的下载地址；不给 `packId` = 全部规则包。 */
export function qubaguExportUrl(packId?: string): string {
  return packId ? `/api/qubagu/export?pack=${encodeURIComponent(packId)}` : "/api/qubagu/export";
}

export function newReplaceRule(): QubaguReplaceRule {
  return {
    kind: "replace", id: crypto.randomUUID(), name: "", sample: "", find: "", regex: true, replacements: [],
    dialogue: false, keepFirst: false, atEnd: false, enabled: true, refinePrompt: "",
  };
}

export function newQuotaRule(): QubaguQuotaRule {
  return { kind: "quota", id: crypto.randomUUID(), name: "", words: [], max: 2, enabled: true, refinePrompt: "" };
}

export function newRulePack(name: string, description: string): QubaguRulePack {
  return { id: crypto.randomUUID(), name, description, enabled: true, rules: [] };
}

/** 把 `packs` 里 id 为 `packId` 的包换成 `transform` 的结果。 */
export function mapPack(packs: QubaguRulePack[], packId: string, transform: (pack: QubaguRulePack) => QubaguRulePack): QubaguRulePack[] {
  return packs.map(pack => (pack.id === packId ? transform(pack) : pack));
}

/** 把包挪 `by` 个位置（超出范围不动）。 */
export function movePack(packs: QubaguRulePack[], packId: string, by: number): QubaguRulePack[] {
  const from = packs.findIndex(pack => pack.id === packId);
  const to = from + by;
  if (from < 0 || to < 0 || to >= packs.length) return packs;
  const next = [...packs];
  next.splice(to, 0, ...next.splice(from, 1));
  return next;
}

export interface QubaguTestResult {
  text: string;
  edits: Array<{ before: string; after: string; rule: string }>;
}

export function testQubagu(text: string): Promise<QubaguTestResult> {
  return api.post<QubaguTestResult>("qubagu/test", { text });
}

export type QubaguMessageAction = "apply" | "restore" | "scan" | "refine";

/** apply / restore 不给 part、edit = 这条消息的全部改动。refine 等模型跑完才返回。 */
export function postQubaguMessageAction(
  conversationId: string,
  messageId: string,
  action: QubaguMessageAction,
  target?: { part: number; edit: number },
): Promise<unknown> {
  return api.post("qubagu/messages", { conversationId, messageId, action, ...target });
}

/** 「a / b / c」→ 候选替换词；空 = 删掉。 */
export function parseReplacements(input: string): string[] {
  const seen = new Set<string>();
  for (const piece of input.split("/")) {
    const word = piece.trim();
    if (word) seen.add(word);
  }
  return [...seen].slice(0, QUBAGU_LIMITS.replacements);
}

export function parseQuotaWords(input: string): string[] {
  return [...new Set(input.split(/[,，、\s]+/).filter(word => word.length > 0))].slice(0, QUBAGU_LIMITS.words);
}

// ---------------------------------------------------------------------------------------------
// 消息上的改动记录（文字 part 的 metadata.qubagu，见 QubaguRecord.kt）

export const QUBAGU_KEY = "qubagu";

/** 一处改动；`start` 是 `before` 在原文里的起点，不随其他改动的应用或撤销变化。 */
export interface QubaguEdit {
  start: number;
  before: string;
  after: string;
  rule: string;
  applied: boolean;
}

export interface QubaguPartRecord {
  /** 文字段在消息 parts 里的下标。 */
  part: number;
  edits: QubaguEdit[];
}

/** Java 的 `String.hashCode()`：记录里存的是写入时正文的这个值。 */
export function javaStringHash(text: string): number {
  let hash = 0;
  for (let i = 0; i < text.length; i++) hash = (Math.imul(31, hash) + text.charCodeAt(i)) | 0;
  return hash;
}

function decodeEdits(raw: unknown, text: string): QubaguEdit[] | null {
  if (typeof raw !== "object" || raw === null) return null;
  if ((raw as Record<string, unknown>).h !== javaStringHash(text)) return null;
  const list = (raw as Record<string, unknown>).edits;
  if (!Array.isArray(list) || list.length === 0) return null;
  const edits: QubaguEdit[] = [];
  for (const item of list) {
    if (typeof item !== "object" || item === null) return null;
    const { s, b, a, r, on } = item as Record<string, unknown>;
    if (typeof s !== "number" || typeof b !== "string" || typeof a !== "string" || typeof r !== "string") return null;
    edits.push({ start: s, before: b, after: a, rule: r, applied: on !== false });
  }
  return edits;
}

/** 每条改动在当前正文里的位置对得上吗（正文是否变过另由记录里的指纹判断，见 `decodeEdits`）。 */
export function qubaguRecordMatches(edits: QubaguEdit[], current: string): boolean {
  let cursor = 0;
  let originalCursor = 0;
  for (const edit of edits) {
    const gap = edit.start - originalCursor;
    if (gap < 0 || cursor + gap > current.length) return false;
    cursor += gap;
    const shown = edit.applied ? edit.after : edit.before;
    if (!current.startsWith(shown, cursor)) return false;
    cursor += shown.length;
    originalCursor = edit.start + edit.before.length;
  }
  return true;
}

export function qubaguRecordsOfParts(
  parts: ReadonlyArray<{ type?: string; text?: unknown; metadata?: unknown }>,
): QubaguPartRecord[] {
  const records: QubaguPartRecord[] = [];
  parts.forEach((part, index) => {
    if (part.type !== "text" || typeof part.text !== "string") return;
    const metadata = part.metadata;
    if (typeof metadata !== "object" || metadata === null) return;
    const edits = decodeEdits((metadata as Record<string, unknown>)[QUBAGU_KEY], part.text);
    if (edits && qubaguRecordMatches(edits, part.text)) records.push({ part: index, edits });
  });
  return records;
}

/** 清单里一处改动占一行：首尾空白去掉，中间的换行显示成 ↵。 */
export function qubaguOnOneLine(text: string): string {
  return text.trim().replace(/\s*\n\s*/g, " ↵ ");
}

/** 改动记录里的规则 id 给人看的名字以外的统称（settings 命名空间）：碎句合并、AI 精修，或已被删掉的规则。 */
export function qubaguRuleLabelKey(rule: string): string {
  return rule === "merge" || rule === "ai" ? `qubagu.rule_label.${rule}` : "qubagu.rule_label.deleted";
}

/** 改动记录里的规则 id（`包 id/规则 id`）对应规则的名字（没名字用规则 id）；找不到返回 null。 */
export function qubaguRuleName(rule: string, config: QubaguConfig | null | undefined): string | null {
  const slash = rule.indexOf("/");
  if (!config || slash < 0) return null;
  const pack = config.packs.find(item => item.id === rule.slice(0, slash));
  const found = pack?.rules.find(item => item.id === rule.slice(slash + 1));
  return found ? found.name || found.id : null;
}
