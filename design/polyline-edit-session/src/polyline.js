// 折线的纯模型与编辑历史：不知道画布、事件和像素，只处理带稳定 ID 的点序列。

/** 返回把某个顶点移到 (x, y) 之后的新点序列；坐标限制在逻辑画布 width × height 之内。 */
export function movePoint(points, id, x, y, width, height) {
  return points.map(p => (p.id === id
    ? { id, x: Math.max(0, Math.min(width, x)), y: Math.max(0, Math.min(height, y)) }
    : p));
}

export function samePoints(a, b) {
  return a.length === b.length && a.every((p, i) => p.id === b[i].id && p.x === b[i].x && p.y === b[i].y);
}

/** 一次编辑改动了哪些顶点：记录这些顶点的前后坐标，而不是整份点序列。 */
function diff(before, after) {
  return after.filter((p, i) => p.x !== before[i].x || p.y !== before[i].y)
    .map(p => ({ id: p.id, from: before.find(q => q.id === p.id), to: p }));
}

/** 把一组顶点改动反向（或正向）应用到当前点序列；某个顶点已被别处改过时返回 null。 */
function applyChanges(points, changes, direction) {
  const [expect, target] = direction === 'undo' ? ['to', 'from'] : ['from', 'to'];
  const next = structuredClone(points);
  for (const change of changes) {
    const p = next.find(q => q.id === change.id);
    if (p.x !== change[expect].x || p.y !== change[expect].y) return null;
    Object.assign(p, { x: change[target].x, y: change[target].y });
  }
  return next;
}

/**
 * 正式文档：点序列加一个版本号；每次提交、撤销、重做或外部更新都让版本加一。
 * history = 'snapshot' 时撤销恢复整份点序列；'command' 时只反向应用这次改动过的顶点。
 */
export class PolylineDocument {
  #points;
  #revision = 0;
  #undo = [];
  #redo = [];
  #history;

  constructor(points, history = 'snapshot') {
    this.#points = structuredClone(points);
    this.#history = history;
  }

  get points() { return structuredClone(this.#points); }
  get revision() { return this.#revision; }
  get historySize() { return { undo: this.#undo.length, redo: this.#redo.length }; }

  /** 一次编辑作为一条历史记录提交；基准版本不是当前版本时拒绝，内容没变时不产生记录。 */
  commit(baseRevision, next) {
    if (baseRevision !== this.#revision) return 'stale';
    if (samePoints(this.#points, next)) return 'unchanged';
    this.#undo.push(this.#history === 'command' ? diff(this.#points, next) : this.#points);
    this.#redo = [];
    this.#replace(next);
    return 'committed';
  }

  undo() {
    return this.#step(this.#undo, this.#redo, 'undo');
  }

  redo() {
    return this.#step(this.#redo, this.#undo, 'redo');
  }

  #step(from, to, direction) {
    if (!from.length) return 'empty';
    const entry = from.at(-1);
    if (this.#history === 'snapshot') {
      from.pop();
      to.push(this.#points);
      this.#replace(entry);
      return 'applied';
    }
    const next = applyChanges(this.#points, entry, direction);
    if (next === null) return 'conflict';          // 这次改动涉及的顶点已被别处修改，不覆盖
    from.pop();
    to.push(entry);
    this.#replace(next);
    return 'applied';
  }

  /** 来自别处的更新（例如另一个客户端），不进入本地撤销栈。 */
  applyExternal(next) {
    this.#replace(next);
  }

  #replace(next) {
    this.#points = structuredClone(next);
    this.#revision += 1;
  }
}
