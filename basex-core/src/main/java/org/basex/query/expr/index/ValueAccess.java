package org.basex.query.expr.index;

import static org.basex.query.QueryText.*;
import static org.basex.util.Token.*;

import java.util.*;

import org.basex.data.*;
import org.basex.index.*;
import org.basex.index.query.*;
import org.basex.query.*;
import org.basex.query.expr.*;
import org.basex.query.expr.path.*;
import org.basex.query.func.*;
import org.basex.query.iter.*;
import org.basex.query.util.*;
import org.basex.query.value.item.*;
import org.basex.query.value.node.*;
import org.basex.query.var.*;
import org.basex.util.*;
import org.basex.util.hash.*;

/**
 * This index class retrieves texts and attribute values from the index.
 *
 * @author BaseX Team 2005-18, BSD License
 * @author Christian Gruen
 */
public final class ValueAccess extends IndexAccess {
  /** Search expression. */
  private Expr expr;
  /** Index type. */
  private final IndexType type;
  /** Parent name test (can be {@code null}). */
  private final NameTest test;
  /** Trim search terms. */
  private boolean trim;

  /**
   * Constructor.
   * @param info input info
   * @param expr search expression
   * @param type index type
   * @param test test test (can be {@code null})
   * @param db index database
   */
  public ValueAccess(final InputInfo info, final Expr expr, final IndexType type,
      final NameTest test, final IndexDb db) {
    super(db, info, type);
    this.expr = expr;
    this.type = type;
    this.test = test;
  }

  /**
   * Sets the trim flag.
   * @param tr trim flag
   * @return self reference
   */
  public ValueAccess trim(final boolean tr) {
    trim = tr;
    return this;
  }

  @Override
  public BasicNodeIter iter(final QueryContext qc) throws QueryException {
    final Data data = db.data(qc, type);
    if(expr.seqType().zeroOrOne()) return iter(expr.item(qc, info), data);

    final ArrayList<BasicNodeIter> iters = new ArrayList<>();
    final Iter iter = expr.iter(qc);
    for(Item item; (item = qc.next(iter)) != null;) iters.add(iter(item, data));
    final int is = iters.size();
    return is == 0 ? BasicNodeIter.EMPTY : is == 1 ? iters.get(0) :
      new Union(info, expr).eval(iters.toArray(new NodeIter[is]), qc).iter();
  }

  /**
   * Returns an index iterator.
   * @param item text item
   * @param data data reference
   * @return iterator
   * @throws QueryException query exception
   */
  private BasicNodeIter iter(final Item item, final Data data) throws QueryException {
    if(item == null) return BasicNodeIter.EMPTY;

    // retrieve and trim text
    final byte[] token = item.string(info), term = trim ? Token.trim(token) : token;

    // special case: empty text node
    // - no element name: return 0 results (empty text nodes are non-existent)
    // - otherwise, return scan-based element iterator
    final int tl = term.length;
    if(tl == 0 && type == IndexType.TEXT)
      return test == null ? BasicNodeIter.EMPTY : scanEmpty(data);

    // check if index is available and if it may contain the requested term
    // otherwise, use sequential scan
    boolean index = data.meta.index(type);
    if(type == IndexType.TEXT || type == IndexType.ATTRIBUTE) {
      index &= tl > 0 && tl <= data.meta.maxlen;
    }

    final IndexIterator ii = index ? data.iter(new StringToken(type, term)) : scan(term, data);
    final int kind = type == IndexType.TEXT ? Data.TEXT : Data.ATTR;
    final DBNode tmp = new DBNode(data, 0, test == null ? kind : Data.ELEM);
    return new DBNodeIter(data) {
      @Override
      public DBNode next() {
        while(ii.more()) {
          if(test == null) {
            tmp.pre(ii.pre());
          } else {
            tmp.pre(data.parent(ii.pre(), kind));
            if(!test.eq(tmp)) continue;
          }
          return tmp.finish();
        }
        return null;
      }
    };
  }

  /**
   * Returns a scan-based index iterator, which looks for text nodes with the specified value.
   * @param data data reference
   * @param value value to be looked up
   * @return node iterator
   */
  private IndexIterator scan(final byte[] value, final Data data) {
    return new IndexIterator() {
      final boolean text = type == IndexType.TEXT;
      final byte kind = text ? Data.TEXT : Data.ATTR;
      final int sz = data.meta.size;
      int pre = -1;

      @Override
      public int pre() {
        return pre;
      }
      @Override
      public boolean more() {
        while(++pre < sz) {
          if(data.kind(pre) == kind) {
            if(eq(data.text(pre, text), value)) return true;
          }
        }
        return false;
      }
      @Override
      public int size() {
        return Math.max(1, sz >>> 1);
      }
    };
  }

  /**
   * Returns a scan-based iterator, which returns elements
   * a) matching the name test and
   * b) having no descendants.
   * @param data data reference
   * @return node iterator
   */
  private BasicNodeIter scanEmpty(final Data data) {
    return new DBNodeIter(data) {
      final DBNode tmp = new DBNode(data, 0, Data.ELEM);
      final int sz = data.meta.size;
      int pre = -1;

      @Override
      public DBNode next() {
        while(++pre < sz) {
          if(data.kind(pre) == Data.ELEM && data.size(pre, Data.ELEM) == 1) {
            tmp.pre(pre);
            if(test == null || test.eq(tmp)) return tmp.finish();
          }
        }
        return null;
      }
    };
  }

  @Override
  public boolean has(final Flag... flags) {
    return expr.has(flags) && super.has(flags);
  }

  @Override
  public boolean removable(final Var var) {
    return expr.removable(var) && super.removable(var);
  }

  @Override
  public VarUsage count(final Var var) {
    return expr.count(var).plus(super.count(var));
  }

  @Override
  public Expr inline(final Var var, final Expr ex, final CompileContext cc) throws QueryException {
    final Expr sub = expr.inline(var, ex, cc);
    if(sub != null) expr = sub;
    final Expr ia = super.inline(var, ex, cc);
    return sub != null || ia != null ? optimize(cc) : null;
  }

  @Override
  public Expr copy(final CompileContext cc, final IntObjMap<Var> vm) {
    return copyType(new ValueAccess(info, expr.copy(cc, vm), type, test, db.copy(cc, vm)));
  }

  @Override
  public boolean accept(final ASTVisitor visitor) {
    return expr.accept(visitor) && super.accept(visitor);
  }

  @Override
  public int exprSize() {
    return expr.exprSize() + super.exprSize();
  }

  @Override
  public boolean equals(final Object obj) {
    if(!(obj instanceof ValueAccess)) return false;
    final ValueAccess v = (ValueAccess) obj;
    return expr.equals(obj) && type == v.type && Objects.equals(test, v.test) && trim == v.trim &&
        super.equals(obj);
  }

  @Override
  public void plan(final FElem plan) {
    addPlan(plan, planElem(INDEX, type, NAME, test), db, expr);
  }

  @Override
  public String toString() {
    final TokenBuilder tb = new TokenBuilder();
    final Function func = type == IndexType.TEXT ? Function._DB_TEXT : type == IndexType.ATTRIBUTE
        ? Function._DB_ATTRIBUTE : Function._DB_TOKEN;
    tb.add(func.args(db.source(), expr).substring(1));
    if(test != null) tb.add("/parent::").addExt(test);
    return tb.toString();
  }
}
