/**
 * A screen of a given width, for specs that measure responsive CSS.
 *
 * A media query follows the width of the WINDOW, and a Karma page cannot
 * resize its window: a component rendered in a 1024 px wide `<div>` still gets
 * the phone rules when the Karma window is narrow (QA D2794: the profile
 * header "passed" on the old stylesheet for that reason). An iframe is a
 * window of its own: {@link openScreen} makes one `width` px wide and copies
 * the page's stylesheets into it — the global sheet and every component style
 * Angular has added to the document so far — so the rules computed inside it
 * are the ones a screen of that width gets.
 *
 * Render the component first (its styles are added when it is created), then
 * open the screen and move the rendered element into it with
 * `screen.body.appendChild(fixture.nativeElement)`.
 */
const frames: HTMLIFrameElement[] = [];

export async function openScreen(width: number, height = 800): Promise<Document> {
  const frame = document.createElement('iframe');
  frame.style.cssText = `width:${width}px;height:${height}px;border:0;position:absolute;left:0;top:0`;
  document.body.appendChild(frame);
  frames.push(frame);
  const doc = frame.contentDocument!;
  doc.open();
  doc.write('<!doctype html><html><head></head><body style="margin:0"></body></html>');
  doc.close();
  const loading: Promise<unknown>[] = [];
  document.querySelectorAll('link[rel="stylesheet"], style').forEach((node) => {
    const copy = doc.importNode(node, true);
    if (copy.nodeName === 'LINK') {
      loading.push(
        new Promise((resolve) => {
          copy.addEventListener('load', resolve);
          copy.addEventListener('error', resolve);
        }),
      );
    }
    doc.head.appendChild(copy);
  });
  await Promise.all(loading);
  return doc;
}

/** Remove every screen opened by the spec (call it in `afterEach`). */
export function closeScreens(): void {
  for (const frame of frames.splice(0)) frame.remove();
}
