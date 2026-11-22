/** The creator assistant (`CreatorAssistantDefinition.ID`): a fixed built-in assistant. */
export const CREATOR_ASSISTANT_ID = "c0ae7a11-7a3d-4b2e-9f1a-2c4e5d6b7a90";

export function isCreatorConversation(assistantId: string | null | undefined): boolean {
  return assistantId === CREATOR_ASSISTANT_ID;
}

export interface CreatorDiffLine {
  path: string;
  before?: string | null;
  after?: string | null;
  secret?: boolean;
}

/** `GET /api/creator/proposals`: the changes the creator assistant proposed in one conversation. */
export interface CreatorProposal {
  id: string;
  conversationId: string;
  status: string;
  statusReason?: string | null;
  risk: string;
  requiresEnhancedConfirm: boolean;
  domains: string[];
  createdAt: number;
  expiresAt: number;
  digest: string;
  diff: CreatorDiffLine[];
  omittedDiffLines: number;
  createdIds: Record<string, string>;
}

export interface CreatorProposalList {
  proposals: CreatorProposal[];
  writeGateOpen: boolean;
  writeGateReasons: string[];
}

export interface CreatorApprovalResult {
  proposalId?: string | null;
  status?: string | null;
  error?: string | null;
}

export const STATUS_PENDING = "pending_approval";

/** Waiting for the user: pending and not past its lifetime. */
export function isPending(proposal: CreatorProposal, now: number): boolean {
  return proposal.status === STATUS_PENDING && proposal.expiresAt > now;
}

export function pendingProposals(proposals: CreatorProposal[], now: number): CreatorProposal[] {
  return proposals.filter((proposal) => isPending(proposal, now));
}

/** Pending ones first (oldest first, the order they would be applied in), then the rest newest first. */
export function orderProposals(proposals: CreatorProposal[], now: number): CreatorProposal[] {
  const pending = pendingProposals(proposals, now).sort((a, b) => a.createdAt - b.createdAt);
  const rest = proposals.filter((proposal) => !isPending(proposal, now)).sort((a, b) => b.createdAt - a.createdAt);
  return [...pending, ...rest];
}

/** i18n key of a status; a pending proposal past its lifetime reads as expired. */
export function statusKey(proposal: CreatorProposal, now: number): string {
  if (proposal.status === STATUS_PENDING && proposal.expiresAt <= now) return "expired";
  const known = new Set([
    "pending_approval", "approved", "applying", "applied", "rejected", "expired", "cancelled", "conflict", "failed", "needs_recovery",
  ]);
  return known.has(proposal.status) ? proposal.status : "other";
}

/** A diff line's path without the `settings.` prefix and with ids shortened, for a compact row label. */
export function shortPath(path: string): string {
  return path
    .replace(/^settings\./, "")
    .replace(/[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-([0-9a-f]{12})/gi, (_, tail: string) => `…${tail.slice(-4)}`);
}

/** After approving, which proposals the server says were applied (for the summary toast). */
export function summarizeApproval(results: CreatorApprovalResult[]): { applied: number; failed: number; firstError: string | null } {
  const applied = results.filter((result) => result.status === "applied").length;
  const failures = results.filter((result) => result.status !== "applied");
  return { applied, failed: failures.length, firstError: failures.find((r) => r.error)?.error ?? null };
}
