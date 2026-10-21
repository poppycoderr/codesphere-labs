// 画布适配层：把指针事件换算成逻辑坐标，维护一次拖动的编辑会话，并把模型或预览画出来。
import { PolylineDocument, movePoint } from './polyline.js';

const WIDTH = 600;
const HEIGHT = 300;
const HIT_RADIUS = 12;

const canvas = document.querySelector('canvas');
const ctx = canvas.getContext('2d');
const history = new URLSearchParams(location.search).get('history') ?? 'snapshot';
const doc = new PolylineDocument([{ id: 'p1', x: 80, y: 80 }, { id: 'p2', x: 240, y: 160 }, { id: 'p3', x: 430, y: 100 }], history);
let session = null;       // { pointerId, vertexId, baseRevision, preview }
let lastOutcome = null;

/** 视口坐标 → 逻辑坐标：按画布当前的显示尺寸换算，与 devicePixelRatio 无关。 */
function toLogical(event) {
  const box = canvas.getBoundingClientRect();
  return { x: (event.clientX - box.left) * WIDTH / box.width, y: (event.clientY - box.top) * HEIGHT / box.height };
}

/** 像素缓冲按 devicePixelRatio 放大，绘制时用变换回到逻辑坐标。 */
function draw() {
  const dpr = window.devicePixelRatio;
  canvas.width = WIDTH * dpr;
  canvas.height = HEIGHT * dpr;
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
  ctx.clearRect(0, 0, WIDTH, HEIGHT);
  const points = session ? session.preview : doc.points;
  const color = session ? '#a46018' : '#126e8c';
  ctx.beginPath();
  points.forEach((p, i) => (i ? ctx.lineTo(p.x, p.y) : ctx.moveTo(p.x, p.y)));
  ctx.lineWidth = 3;
  ctx.strokeStyle = color;
  ctx.stroke();
  ctx.fillStyle = color;
  for (const p of points) {
    ctx.beginPath();
    ctx.arc(p.x, p.y, 8, 0, Math.PI * 2);
    ctx.fill();
    ctx.fillText(p.id, p.x + 12, p.y - 12);
  }
}

function endSession(outcome) {
  lastOutcome = outcome;
  session = null;
  draw();
}

canvas.addEventListener('pointerdown', event => {
  if (session || !event.isPrimary || event.button !== 0) return;
  const at = toLogical(event);
  const hit = doc.points.find(p => Math.hypot(p.x - at.x, p.y - at.y) <= HIT_RADIUS);
  if (!hit) return;
  session = { pointerId: event.pointerId, vertexId: hit.id, baseRevision: doc.revision, preview: doc.points };
  canvas.setPointerCapture(event.pointerId);     // 拖出画布后仍能收到 move 与 up
  draw();
});

canvas.addEventListener('pointermove', event => {
  if (!session || session.pointerId !== event.pointerId) return;
  const at = toLogical(event);
  session.preview = movePoint(session.preview, session.vertexId, at.x, at.y, WIDTH, HEIGHT);
  draw();
});

canvas.addEventListener('pointerup', event => {
  if (!session || session.pointerId !== event.pointerId) return;
  endSession(doc.commit(session.baseRevision, session.preview));
});

canvas.addEventListener('pointercancel', () => session && endSession('cancelled'));
canvas.addEventListener('lostpointercapture', () => session && endSession('cancelled'));
window.addEventListener('keydown', event => event.key === 'Escape' && session && endSession('cancelled'));
document.querySelector('#undo').addEventListener('click', () => { lastOutcome = doc.undo(); draw(); });
document.querySelector('#redo').addEventListener('click', () => { lastOutcome = doc.redo(); draw(); });

// 测试入口：读取状态、模拟一次来自别处的更新
window.editor = {
  snapshot: () => ({
    points: doc.points, revision: doc.revision, ...doc.historySize,
    preview: session ? structuredClone(session.preview) : null, lastOutcome,
    bufferWidth: canvas.width, cssWidth: canvas.getBoundingClientRect().width,
  }),
  applyExternal: points => { doc.applyExternal(points); draw(); },
};
draw();
