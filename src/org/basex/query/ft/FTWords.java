package org.basex.query.ft;

import static org.basex.query.QueryText.*;
import java.io.IOException;
import org.basex.data.Data;
import org.basex.data.FTMatches;
import org.basex.data.MetaData;
import org.basex.data.Serializer;
import org.basex.index.FTIndexIterator;
import org.basex.query.IndexContext;
import org.basex.query.QueryContext;
import org.basex.query.QueryException;
import org.basex.query.expr.Expr;
import org.basex.query.ft.FTOpt.FTMode;
import org.basex.query.item.FTItem;
import org.basex.query.item.Item;
import org.basex.query.item.Str;
import org.basex.query.item.Type;
import org.basex.query.iter.FTIter;
import org.basex.query.iter.Iter;
import org.basex.query.util.Err;
import org.basex.util.Token;
import org.basex.util.TokenBuilder;
import org.basex.util.Tokenizer;

/**
 * FTWords expression.
 *
 * @author Workgroup DBIS, University of Konstanz 2005-09, ISC License
 * @author Christian Gruen
 */
public final class FTWords extends FTExpr {
  /** Data reference. */
  Data data;
  /** Single word. */
  byte[] txt;
  /** Fast evaluation. */
  boolean fast;

  /** All matches. */
  private final FTMatches all = new FTMatches();
  /** Minimum and maximum occurrences. */
  private Expr[] occ;
  /** Search mode. */
  private FTMode mode;
  /** Expression list. */
  private Expr query;
  /** Token number. */
  private byte tokNum;
  /** Standard evaluation. */
  private boolean simple;

  /**
   * Sequential constructor.
   * @param e expression
   * @param m search mode
   * @param o occurrences
   */
  public FTWords(final Expr e, final FTMode m, final Expr[] o) {
    query = e;
    mode = m;
    occ = o;
  }

  /**
   * Index constructor.
   * @param d data reference
   * @param t text
   * @param f fast evaluation
   */
  public FTWords(final Data d, final byte[] t, final boolean f) {
    data = d;
    txt = t;
    fast = f;
  }

  @Override
  public FTExpr comp(final QueryContext ctx) throws QueryException {
    if(occ != null) {
      for(int o = 0; o < occ.length; o++) occ[o] = occ[o].comp(ctx);
    }
    query = query.comp(ctx);
    if(query instanceof Str) txt = ((Str) query).str();
    simple = mode == FTMode.ANY && txt != null && occ == null;
    fast = ctx.ftfast && occ == null;
    return this;
  }

  @Override
  public FTItem atomic(final QueryContext ctx) throws QueryException {
    if(tokNum == 0) tokNum = ++ctx.ftoknum;
    all.reset(tokNum);

    final int c = contains(ctx);
    double s = c == 0 ? 0 : ctx.score.word(c, ctx.fttoken.size());

    // evaluate weight
    final Expr w = ctx.ftopt.weight;
    if(w != null) {
      final double d = checkDbl(w, ctx);
      if(Math.abs(d) > 1000) Err.or(FTWEIGHT, d);
      s *= d;
    }
    return new FTItem(all, s);
  }

  @Override
  public FTIter iter(final QueryContext ctx) {
    return new FTIter() {
      /** Index iterator. */
      FTIndexIterator iat;

      @Override
      public FTItem next() {
        if(iat == null) {
          final Tokenizer ft = new Tokenizer(txt, ctx.ftopt, fast);
          // more than one token: deactivate fast processing
          ft.fast &= ft.count() == 1;
          ft.init();
          while(ft.more()) {
            final FTIndexIterator it = (FTIndexIterator) data.ids(ft);
            iat = iat == null ? it : FTIndexIterator.intersect(iat, it);
          }
          iat.setTokenNum(++ctx.ftoknum);
        }
        return iat.more() ? new FTItem(iat.matches(), data, iat.next()) : null;
      }
    };
  }

  /**
   * Evaluates the full-text match.
   * @param ctx query context
   * @return length value, used for scoring
   * @throws QueryException xquery exception
   */
  private int contains(final QueryContext ctx) throws QueryException {
    // speed up default case
    final FTOpt opt = ctx.ftopt;
    if(simple) return opt.contains(txt, ctx.fttoken, all, fast) == 0 ?
        0 : txt.length;

    // process special cases
    final Iter iter = ctx.iter(query);
    int len = 0;
    int o = 0;
    byte[] it;

    switch(mode) {
      case ALL:
        while((it = nextStr(iter)) != null) {
          final int oc = opt.contains(it, ctx.fttoken, all, fast);
          if(oc == 0) return 0;
          len += it.length;
          o += oc / ctx.ftopt.qu.count();
        }
        break;
      case ALLWORDS:
        while((it = nextStr(iter)) != null) {
          for(final byte[] t : Token.split(it, ' ')) {
            final int oc = opt.contains(t, ctx.fttoken, all, fast);
            if(oc == 0) return 0;
            len += t.length;
            o += oc;
          }
        }
        break;
      case ANY:
        while((it = nextStr(iter)) != null) {
          o += opt.contains(it, ctx.fttoken, all, fast);
          len += it.length;
        }
        break;
      case ANYWORD:
        while((it = nextStr(iter)) != null) {
          for(final byte[] t : Token.split(it, ' ')) {
            final int oc = opt.contains(t, ctx.fttoken, all, fast);
            len += t.length;
            o += oc;
          }
        }
        break;
      case PHRASE:
        final TokenBuilder tb = new TokenBuilder();
        while((it = nextStr(iter)) != null) {
          tb.add(it);
          tb.add(' ');
        }
        o += opt.contains(tb.finish(), ctx.fttoken, all, fast);
        len += tb.size;
        break;
    }

    long mn = 1;
    long mx = Long.MAX_VALUE;
    if(occ != null) {
      mn = checkItr(occ[0], ctx);
      mx = checkItr(occ[1], ctx);
    }
    return o < mn || o > mx ? 0 : Math.max(1, len);
  }

  /**
   * Checks if the next item is a string.
   * Returns a token representation or an exception.
   * @param iter iterator to be checked
   * @return item
   * @throws QueryException evaluation exception
   */
  private byte[] nextStr(final Iter iter) throws QueryException {
    final Item it = iter.next();
    if(it == null) return null;
    if(!it.s() && !it.u()) Err.type(info(), Type.STR, it);
    return it.str();
  }

  @Override
  public boolean indexAccessible(final IndexContext ic) {
    /*
     * If the following conditions yield true, the index is accessed:
     * - the query is a simple String item
     * - no FTTimes option and no weight is specified
     * - FTMode is different to ANY, ALL and PHRASE
     * - case sensitivity, diacritics and stemming flags comply with index
     * - no stop words are specified
     */
    data = ic.data;
    final MetaData md = data.meta;
    final FTOpt fto = ic.ctx.ftopt;
    if(txt == null || occ != null || ic.ctx.ftopt.weight != null ||
        mode != FTMode.ANY && mode != FTMode.ALL && mode != FTMode.PHRASE ||
        md.ftcs != fto.is(FTOpt.CS) || md.ftdc != fto.is(FTOpt.DC) ||
        md.ftst != fto.is(FTOpt.ST) || fto.sw != null) return false;

    // limit index access to trie version and simple wildcard patterns
    if(fto.is(FTOpt.WC)) {
      if(md.ftfz || txt[0] == '.') return false;
      int d = 0;
      for(final byte w : txt) {
        if(w == '{' || w == '\\' || w == '.' && ++d > 1) return false;
      }
    }

    // summarize number of hits; break loop if no hits are expected
    final Tokenizer ft = new Tokenizer(txt, fto, fast);
    ic.is = 0;
    while(ft.more()) {
      final double s = data.nrIDs(ft);
      if(s == 0) {
        ic.is = 0;
        break;
      }
      ic.is += s;
    }
    return true;
  }

  @Override
  public void plan(final Serializer ser) throws IOException {
    ser.openElement(this);
    if(txt != null) ser.text(txt);
    else query.plan(ser);
    ser.closeElement();
  }

  @Override
  public String toString() {
    return query.toString();
  }

  @Override
  public boolean usesExclude() {
    return occ != null;
  }
}
