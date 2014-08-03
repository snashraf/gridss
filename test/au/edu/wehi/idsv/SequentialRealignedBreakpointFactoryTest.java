package au.edu.wehi.idsv;

import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import htsjdk.samtools.SAMRecord;

import java.util.List;

import org.junit.Test;

import com.google.common.collect.Iterators;

public class SequentialRealignedBreakpointFactoryTest extends TestHelper {
	public SequentialRealignedBreakpointFactory getFactory(List<SAMRecord> data) {
		return new SequentialRealignedBreakpointFactory(Iterators.peekingIterator(data.iterator()));
	}
	public class TestDirectedBreakend implements DirectedEvidence {
		private String id;
		private BreakendSummary location;
		public TestDirectedBreakend(int referenceIndex, int start, String id) {
			this.id = id;
			this.location = new BreakendSummary(referenceIndex, BreakendDirection.Forward, start, start);
		}
		@Override
		public BreakendSummary getBreakendSummary() {
			return location;
		}
		@Override
		public String getEvidenceID() {
			return id;
		}
		@Override
		public byte[] getBreakendSequence() {
			return null;
		}
		@Override
		public byte[] getBreakendQuality() {
			return null;
		}
		@Override
		public EvidenceSource getEvidenceSource() {
			return SES();
		}
		@Override
		public int getLocalMapq() {
			return 0;
		}
		@Override
		public int getLocalBaseLength() {
			return 0;
		}
		@Override
		public int getLocalBaseCount() {
			return 0;
		}
		@Override
		public int getLocalMaxBaseQual() {
			return 0;
		}
		@Override
		public int getLocalTotalBaseQual() {
			return 0;
		}
	}
	@Test
	public void should_match_by_read_name() {
		SequentialRealignedBreakpointFactory factory = getFactory(L(withReadName("0#1#n1", Read(0, 1, 1))));
		SAMRecord r = factory.findAssociatedSAMRecord(new TestDirectedBreakend(0, 1, "n1"));
		assertNotNull(r);
	}
	@Test
	public void should_match_sequential_records() {
		SequentialRealignedBreakpointFactory factory = getFactory(L(
			withReadName("0#1#n1", Read(0, 1, "1M")),
			withReadName("0#1#n2", Read(0, 1, "1M")),
			withReadName("0#1#n3", Read(0, 1, "1M")),
			withReadName("0#2#n4", Read(0, 1, "1M")),
			withReadName("1#1#n5", Read(0, 1, "1M"))
			));
		assertNotNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(0, 1, "n1")));
		assertNotNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(0, 1, "n3")));
		assertNotNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(0, 1, "n2")));
		assertNotNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(0, 2, "n4")));
		assertNotNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(1, 1, "n5")));
	}
	@Test(expected=IllegalStateException.class)
	public void should_fail_during_non_sequential_traversal() {
		SequentialRealignedBreakpointFactory factory = getFactory(L(
				withReadName("0#1#n1", Read(0, 1, 1)),
				withReadName("1#1#n5", Read(0, 1, 1))
				));
		assertNotNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(1, 1, "n5")));
		assertNull(factory.findAssociatedSAMRecord(new TestDirectedBreakend(0, 1, "n1")));
	}
}
