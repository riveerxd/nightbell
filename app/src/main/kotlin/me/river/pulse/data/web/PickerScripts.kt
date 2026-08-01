package me.river.pulse.data.web

import me.river.pulse.domain.ElementTarget
import kotlinx.serialization.json.JsonPrimitive

/**
 * JavaScript injected into the preview [android.webkit.WebView].
 *
 * Two jobs:
 *  1. **Capture** — let the user tap any node and derive a durable signature for
 *     it (id → stable data-attribute → shortest unique CSS path → absolute
 *     XPath → text fingerprint).
 *  2. **Locate** — at check time, resolve that signature again, degrading through
 *     the strategies in order so a cosmetic markup change doesn't false-alarm.
 */
object PickerScripts {

    const val BRIDGE_NAME = "PulseBridge"

    private fun js(value: String): String = JsonPrimitive(value).toString()

    /** Shared selector-derivation helpers, reused by both scripts. */
    private val HELPERS = """
        function __pEsc(s){
          try { return (window.CSS && CSS.escape) ? CSS.escape(s) : String(s).replace(/([^\w-])/g, '\\${'$'}1'); }
          catch(e){ return String(s); }
        }
        function __pText(el){
          if(!el) return '';
          var t = el.innerText || el.textContent || '';
          return t.replace(/\s+/g, ' ').trim();
        }
        function __pStableClass(c){
          if(!c) return false;
          if(c.length < 2 || c.length > 40) return false;
          if(/^(css|sc|jsx|emotion|MuiBox|ng)-/.test(c)) return false;
          if(/\d{4,}/.test(c)) return false;
          if(/^_[A-Za-z0-9]{4,}${'$'}/.test(c)) return false;
          return true;
        }
        function __pUnique(sel){
          try { return document.querySelectorAll(sel).length === 1; } catch(e){ return false; }
        }
        function __pSelector(el){
          if(!el || el.nodeType !== 1) return '';
          if(el.id && __pUnique('#' + __pEsc(el.id))) return '#' + __pEsc(el.id);
          var attrs = ['data-testid','data-test','data-qa','data-cy','aria-label','name','itemprop'];
          for(var a = 0; a < attrs.length; a++){
            var v = el.getAttribute && el.getAttribute(attrs[a]);
            if(v && v.length < 80){
              var s = el.tagName.toLowerCase() + '[' + attrs[a] + '="' + v.replace(/"/g,'\\"') + '"]';
              if(__pUnique(s)) return s;
            }
          }
          var parts = [], node = el, depth = 0;
          while(node && node.nodeType === 1 && depth < 8){
            if(node.id && __pUnique('#' + __pEsc(node.id))){ parts.unshift('#' + __pEsc(node.id)); break; }
            var part = node.tagName.toLowerCase();
            var raw = (typeof node.className === 'string') ? node.className.trim() : '';
            var cls = raw ? raw.split(/\s+/).filter(__pStableClass).slice(0, 2) : [];
            if(cls.length) part += '.' + cls.map(__pEsc).join('.');
            var parent = node.parentElement;
            if(parent){
              var same = Array.prototype.filter.call(parent.children, function(c){ return c.tagName === node.tagName; });
              if(same.length > 1) part += ':nth-of-type(' + (Array.prototype.indexOf.call(same, node) + 1) + ')';
            }
            parts.unshift(part);
            var candidate = parts.join(' > ');
            if(__pUnique(candidate)) return candidate;
            node = parent;
            if(!node || node.tagName === 'HTML') break;
            depth++;
          }
          return parts.join(' > ');
        }
        function __pXPath(el){
          if(!el || el.nodeType !== 1) return '';
          if(el.id) return '//*[@id="' + el.id + '"]';
          var parts = [], node = el;
          while(node && node.nodeType === 1 && node.tagName !== 'HTML'){
            var idx = 1, sib = node.previousElementSibling;
            while(sib){ if(sib.tagName === node.tagName) idx++; sib = sib.previousElementSibling; }
            parts.unshift(node.tagName.toLowerCase() + '[' + idx + ']');
            node = node.parentElement;
          }
          return '/html/' + parts.join('/');
        }
    """.trimIndent()

    /**
     * Installed once per page load. Adds hover + selection affordances and pipes
     * the derived signature back to Kotlin through the JS bridge.
     */
    val BOOTSTRAP: String = """
        (function(){
          if (window.__pulseInstalled) { window.__pulseRehighlight && window.__pulseRehighlight(); return 'already'; }
          window.__pulseInstalled = true;
          window.__pulsePickMode = window.__pulsePickMode || false;
          $HELPERS

          var style = document.createElement('style');
          style.setAttribute('data-pulse','1');
          style.textContent = [
            '.__pulse_hover{outline:2px dashed #6EE7FF !important;outline-offset:2px !important;',
            'background-color:rgba(110,231,255,0.10) !important;cursor:crosshair !important;}',
            '.__pulse_picked{outline:3px solid #B388FF !important;outline-offset:2px !important;',
            'background-color:rgba(179,136,255,0.16) !important;box-shadow:0 0 0 6px rgba(179,136,255,0.18) !important;}'
          ].join('');
          (document.head || document.documentElement).appendChild(style);

          var hovered = null, picked = null;

          function clearHover(){ if(hovered){ hovered.classList.remove('__pulse_hover'); hovered = null; } }
          function setPicked(el){
            if(picked) picked.classList.remove('__pulse_picked');
            picked = el;
            if(picked) picked.classList.add('__pulse_picked');
          }
          window.__pulseRehighlight = function(){
            if(window.__pulseLastSelector){
              try {
                var el = document.querySelector(window.__pulseLastSelector);
                if(el) setPicked(el);
              } catch(e){}
            }
          };
          window.__pulseSetPickMode = function(on){
            window.__pulsePickMode = !!on;
            if(!on) clearHover();
            document.documentElement.style.webkitUserSelect = on ? 'none' : '';
            return window.__pulsePickMode;
          };
          window.__pulseClear = function(){ setPicked(null); window.__pulseLastSelector = null; clearHover(); };

          function describe(el){
            var sel = __pSelector(el);
            window.__pulseLastSelector = sel;
            var cls = (typeof el.className === 'string') ? el.className.trim().split(/\s+/).filter(__pStableClass).slice(0,3).join(' ') : '';
            var count = 1;
            try { count = document.querySelectorAll(sel).length; } catch(e){}
            return {
              cssSelector: sel,
              xpath: __pXPath(el),
              elementId: el.id || '',
              tagName: el.tagName.toLowerCase(),
              classSignature: cls,
              text: __pText(el).slice(0, 400),
              html: (el.outerHTML || '').slice(0, 300),
              matchCount: count,
              unique: count === 1
            };
          }

          function onOver(e){
            if(!window.__pulsePickMode) return;
            var el = e.target;
            if(!el || el.nodeType !== 1) return;
            if(el === hovered) return;
            clearHover();
            hovered = el;
            el.classList.add('__pulse_hover');
          }

          function onPick(e){
            if(!window.__pulsePickMode) return;
            var el = e.target;
            if(!el || el.nodeType !== 1) return;
            e.preventDefault();
            e.stopPropagation();
            if(e.stopImmediatePropagation) e.stopImmediatePropagation();
            clearHover();
            setPicked(el);
            try {
              var payload = describe(el);
              if(window.$BRIDGE_NAME && window.$BRIDGE_NAME.onPick){
                window.$BRIDGE_NAME.onPick(JSON.stringify(payload));
              }
            } catch(err){
              if(window.$BRIDGE_NAME && window.$BRIDGE_NAME.onError){
                window.$BRIDGE_NAME.onError(String(err));
              }
            }
            return false;
          }

          document.addEventListener('mouseover', onOver, true);
          document.addEventListener('touchstart', onOver, true);
          document.addEventListener('click', onPick, true);
          document.addEventListener('mousedown', function(e){ if(window.__pulsePickMode){ e.preventDefault(); e.stopPropagation(); } }, true);

          if(window.$BRIDGE_NAME && window.$BRIDGE_NAME.onReady){
            window.$BRIDGE_NAME.onReady(document.title || '');
          }
          return 'installed';
        })();
    """.trimIndent()

    fun setPickMode(active: Boolean): String =
        "(function(){ return window.__pulseSetPickMode ? window.__pulseSetPickMode($active) : false; })();"

    const val CLEAR_SELECTION: String =
        "(function(){ if(window.__pulseClear) window.__pulseClear(); return true; })();"

    /** Re-resolves a stored [ElementTarget]; returns a JSON string. */
    fun locate(target: ElementTarget): String = """
        (function(){
          $HELPERS
          var ID = ${js(target.elementId)};
          var SEL = ${js(target.cssSelector)};
          var XP = ${js(target.xpath)};
          var SNIP = ${js(target.textSnippet)};
          var ATTR = ${js(target.attribute)};
          var el = null, how = '';
          try { if(ID){ el = document.getElementById(ID); if(el) how = 'id'; } } catch(e){}
          if(!el && SEL){ try { el = document.querySelector(SEL); if(el) how = 'css'; } catch(e){} }
          if(!el && XP){
            try {
              var r = document.evaluate(XP, document, null, 9, null);
              el = r ? r.singleNodeValue : null;
              if(el) how = 'xpath';
            } catch(e){}
          }
          if(!el && SNIP){
            try {
              var all = document.querySelectorAll('body *');
              for(var i = 0; i < all.length; i++){
                var n = all[i];
                if(n.children.length === 0 && __pText(n) === SNIP){ el = n; how = 'text-exact'; break; }
              }
              if(!el){
                for(var j = 0; j < all.length; j++){
                  var m = all[j];
                  if(m.children.length === 0 && __pText(m).indexOf(SNIP) >= 0){ el = m; how = 'text-partial'; break; }
                }
              }
            } catch(e){}
          }
          if(!el){
            return JSON.stringify({ found:false, how:'', text:'', title: document.title || '', nodes: document.querySelectorAll('*').length });
          }
          var visible = true;
          try {
            var rect = el.getBoundingClientRect();
            var cs = window.getComputedStyle(el);
            visible = !!(rect.width || rect.height) && cs.visibility !== 'hidden' && cs.display !== 'none';
          } catch(e){}
          return JSON.stringify({
            found: true,
            how: how,
            visible: visible,
            text: __pText(el).slice(0, 600),
            attrValue: ATTR ? (el.getAttribute(ATTR) || '') : '',
            html: (el.outerHTML || '').slice(0, 300),
            title: document.title || '',
            nodes: document.querySelectorAll('*').length
          });
        })();
    """.trimIndent()

    /** Cheap readiness probe used while waiting for SPA hydration. */
    const val READY_PROBE: String =
        "(function(){ return JSON.stringify({ nodes: document.querySelectorAll('*').length, title: document.title || '', state: document.readyState }); })();"
}
