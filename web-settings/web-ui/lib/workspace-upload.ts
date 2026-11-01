import type { WorkspaceStorageArea } from "@overlay/types/workspace";
import { ApiError } from "~/services/api";
import {
  joinWorkspacePath,
  parentWorkspacePath,
  workspaceApi,
} from "@overlay/lib/workspace";

const UPLOAD_CONCURRENCY = 4;

/** 简易 p-limit：最多 concurrency 个任务并行跑。 */
async function mapWithConcurrency<T>(
  items: T[],
  concurrency: number,
  task: (item: T) => Promise<void>,
): Promise<void> {
  let nextIndex = 0;
  const workers = Array.from({ length: Math.min(concurrency, items.length) }, async () => {
    while (nextIndex < items.length) {
      const item = items[nextIndex++];
      await task(item);
    }
  });
  await Promise.all(workers);
}

/** mkdir 只吞“路径/已存在”这类 400 错误，其余（网络断开、404 等）继续抛出。 */
function isBenignMkdirError(error: unknown): boolean {
  return error instanceof ApiError && error.code === 400;
}

export interface WorkspaceUploadItem {
  file: File;
  /** 相对被拖入目录的路径，如 "docs/a.txt"；平铺文件就是文件名 */
  relativePath: string;
}

export interface CollectedUploads {
  items: WorkspaceUploadItem[];
  /** 需要显式创建的空目录（相对路径） */
  directories: string[];
}

function getAsEntry(item: DataTransferItem): FileSystemEntry | null {
  const withWebkit = item as DataTransferItem & {
    webkitGetAsEntry?: () => FileSystemEntry | null;
  };
  if (typeof withWebkit.webkitGetAsEntry !== "function") return null;
  return withWebkit.webkitGetAsEntry();
}

function readEntryFile(entry: FileSystemFileEntry): Promise<File> {
  return new Promise((resolve, reject) => {
    entry.file(resolve, reject);
  });
}

function readAllEntries(reader: FileSystemDirectoryReader): Promise<FileSystemEntry[]> {
  // Chrome 每次 readEntries 最多返回 100 条，必须循环读到空为止
  return new Promise((resolve, reject) => {
    const all: FileSystemEntry[] = [];
    const readBatch = () => {
      reader.readEntries((batch) => {
        if (batch.length === 0) {
          resolve(all);
        } else {
          all.push(...batch);
          readBatch();
        }
      }, reject);
    };
    readBatch();
  });
}

async function walkEntry(
  entry: FileSystemEntry,
  parentPath: string,
  collected: CollectedUploads,
): Promise<void> {
  if (entry.isFile) {
    const file = await readEntryFile(entry as FileSystemFileEntry);
    collected.items.push({ file, relativePath: joinWorkspacePath(parentPath, entry.name) });
    return;
  }
  if (entry.isDirectory) {
    const dirPath = joinWorkspacePath(parentPath, entry.name);
    const children = await readAllEntries((entry as FileSystemDirectoryEntry).createReader());
    if (children.length === 0) {
      collected.directories.push(dirPath);
      return;
    }
    for (const child of children) {
      await walkEntry(child, dirPath, collected);
    }
  }
}

/**
 * 从拖拽 DataTransfer 收集文件：优先用 webkitGetAsEntry() 递归遍历目录并保留目录结构；
 * 不可用时回退 dataTransfer.files（平铺）。
 */
export async function collectDroppedUploads(dataTransfer: DataTransfer): Promise<CollectedUploads> {
  const collected: CollectedUploads = { items: [], directories: [] };
  const entries = Array.from(dataTransfer.items ?? [])
    .filter((item) => item.kind === "file")
    .map((item) => getAsEntry(item))
    .filter((entry): entry is FileSystemEntry => entry !== null);

  if (entries.length === 0) {
    collected.items = filesToUploadItems(dataTransfer.files ?? []);
    return collected;
  }

  for (const entry of entries) {
    await walkEntry(entry, "", collected);
  }
  return collected;
}

/** 普通 <input type="file"> 的 FileList 转 items（relativePath = file.name） */
export function filesToUploadItems(files: Iterable<File>): WorkspaceUploadItem[] {
  return Array.from(files, (file) => ({ file, relativePath: file.name }));
}

/**
 * 上传到 baseDir 之下：先按深度升序创建所有空目录，再并发上传文件（限 4）；
 * 单文件失败不影响其余文件，返回统计。
 */
export async function uploadWorkspaceItems(
  id: string,
  area: WorkspaceStorageArea,
  baseDir: string,
  collected: CollectedUploads,
): Promise<{ uploaded: number; failed: number }> {
  const directories = [...new Set(collected.directories)].sort(
    (a, b) => a.split("/").length - b.split("/").length,
  );
  for (const dir of directories) {
    try {
      await workspaceApi.mkdir(id, area, joinWorkspacePath(baseDir, dir));
    } catch (error) {
      if (!isBenignMkdirError(error)) {
        throw error;
      }
      // 目录可能已存在，忽略
    }
  }

  let uploaded = 0;
  let failed = 0;
  await mapWithConcurrency(collected.items, UPLOAD_CONCURRENCY, async (item) => {
    const parent = parentWorkspacePath(item.relativePath);
    const targetDir = parent ? joinWorkspacePath(baseDir, parent) : baseDir;
    try {
      await workspaceApi.upload(id, area, targetDir, item.file);
      uploaded += 1;
    } catch {
      failed += 1;
    }
  });
  return { uploaded, failed };
}
