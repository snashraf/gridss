package au.edu.wehi.idsv;

import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.function.Supplier;

import org.apache.commons.lang3.tuple.Pair;

import com.google.common.collect.ImmutableList;
import com.google.common.collect.Iterators;

import htsjdk.samtools.QueryInterval;
import htsjdk.samtools.util.CloseableIterator;
import htsjdk.samtools.util.CloserUtil;
/**
 * Calls breakpoints from the given evidence
 * 
 * @author Daniel Cameron
 */
public class VariantCallIterator implements CloseableIterator<VariantContextDirectedBreakpoint> {
	private static final List<Pair<BreakendDirection, BreakendDirection>> DIRECTION_ORDER = ImmutableList.of(
			Pair.of(BreakendDirection.Forward, BreakendDirection.Forward),
			Pair.of(BreakendDirection.Forward, BreakendDirection.Backward),
			Pair.of(BreakendDirection.Backward, BreakendDirection.Forward),
			Pair.of(BreakendDirection.Backward, BreakendDirection.Backward));
	private final ProcessingContext processContext;
	private final VariantIdGenerator idGenerator;
	private final Supplier<Iterator<DirectedEvidence>> iteratorGenerator;
	private final QueryInterval[] filterInterval;
	private Iterator<VariantContextDirectedBreakpoint> currentIterator;
	private Iterator<DirectedEvidence> currentUnderlyingIterator;
	private int currentDirectionOrdinal;
	public VariantCallIterator(ProcessingContext processContext, Iterable<DirectedEvidence> evidence) throws InterruptedException {
		this.processContext = processContext;
		this.idGenerator = new SequentialIdGenerator("gridss");
		this.iteratorGenerator = () -> evidence.iterator();
		this.filterInterval = null;
		this.currentDirectionOrdinal = 0;
		reinitialiseIterator();
	}
	public VariantCallIterator(AggregateEvidenceSource source) {
		this.processContext = source.getContext();
		this.idGenerator = new SequentialIdGenerator("gridss");
		this.iteratorGenerator = () -> source.iterator();
		this.filterInterval = null;
		this.currentDirectionOrdinal = 0;
		reinitialiseIterator();
	}
	public VariantCallIterator(AggregateEvidenceSource source, QueryInterval[] interval, int intervalNumber) {
		this.processContext = source.getContext();
		this.idGenerator = new SequentialIdGenerator(String.format("gridss%d_", intervalNumber));
		int expandBy = source.getMaxConcordantFragmentSize() + 1;
		QueryInterval[] expanded = QueryIntervalUtil.padIntervals(processContext.getDictionary(), interval, expandBy);
		this.iteratorGenerator = () -> source.iterator(expanded);
		this.filterInterval = interval;
		this.currentDirectionOrdinal = 0;
		reinitialiseIterator();
	}
	private void reinitialiseIterator() {
		assert(currentIterator == null || !currentIterator.hasNext());
		CloserUtil.close(currentIterator);
		CloserUtil.close(currentUnderlyingIterator);
		if (currentDirectionOrdinal >= DIRECTION_ORDER.size()) return;
		currentUnderlyingIterator = iteratorGenerator.get();
		currentIterator = new MaximalEvidenceCliqueIterator(
				processContext,
				currentUnderlyingIterator,
				DIRECTION_ORDER.get(currentDirectionOrdinal).getLeft(),
				DIRECTION_ORDER.get(currentDirectionOrdinal).getRight(),
				idGenerator);
		if (filterInterval != null) {
			currentIterator = Iterators.filter(currentIterator, v -> {
				BreakpointSummary bs = v.getBreakendSummary();
				return QueryIntervalUtil.overlaps(filterInterval, bs.referenceIndex, bs.start) || 
						QueryIntervalUtil.overlaps(filterInterval, bs.referenceIndex2, bs.start2);
			});
		}
	}
	@Override
	public boolean hasNext() {
		if (currentDirectionOrdinal >= DIRECTION_ORDER.size()) return false;
		if (!currentIterator.hasNext()) {
			currentDirectionOrdinal++;
			reinitialiseIterator();
			return hasNext();
		}
		return true;
	}
	@Override
	public VariantContextDirectedBreakpoint next() {
		if (!hasNext()) throw new NoSuchElementException();
		return currentIterator.next();
	}
	@Override
	public void close() {
		CloserUtil.close(currentIterator);
		CloserUtil.close(currentUnderlyingIterator);
	}
}
 