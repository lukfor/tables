package lukfor.tables.rows;

import java.io.IOException;
import java.util.List;
import java.util.Vector;

import lukfor.tables.rows.filters.IRowFilter;

public class RowSelectionProcessor implements IRowProcessor {

	private IRowFilter filter;

	private List<Boolean> bitmask = new Vector<Boolean>();

	public RowSelectionProcessor(IRowFilter filter) {
		this.filter = filter;
	}

	public void process(Row row) throws IOException {
		bitmask.add(filter.accepts(row));
	}

	public List<Boolean> getBitmask() {
		return bitmask;
	}
}
