package lukfor.tables;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Vector;

import com.jakewharton.fliptables.FlipTable;

import lukfor.tables.columns.AbstractColumn;
import lukfor.tables.columns.ColumnType;
import lukfor.tables.columns.ColumnTypeDetector;
import lukfor.tables.columns.types.StringColumn;
import lukfor.tables.rows.IRowAggregator;
import lukfor.tables.rows.IRowMapper;
import lukfor.tables.rows.IRowProcessor;
import lukfor.tables.rows.Row;
import lukfor.tables.rows.TableIndex;
import lukfor.tables.rows.mappers.BinRowMapper;
import lukfor.tables.rows.processors.RowCopyProcessor;
import lukfor.tables.rows.processors.RowGroupProcessor;
import lukfor.tables.utils.GroupByBuilder;

public class Table {

	private String name;

	protected List<AbstractColumn> storage = new Vector<AbstractColumn>();

	private RowOperations rows;

	private ColumnOperations columns;

	private static boolean logging = true;

	public Table(String name) {
		this.name = name;
		rows = new RowOperations(this);
		columns = new ColumnOperations(this);
	}

	public Object get(int index, String column) {
		return columns.get(column).get(index);
	}

	public Object get(int index, int column) {
		return columns.get(column).get(index);
	}

	public AbstractColumn getColumn(String name) {
		return columns.get(name);
	}

	public AbstractColumn getColumn(int index) {
		return columns.get(index);
	}

	public void forEachRow(final IRowProcessor processor) throws IOException {
		assertsNotEmpty();
		for (int i = 0; i < getRows().getSize(); i++) {
			Row row = rows.get(i);
			processor.process(row);
		}
	}

	public ColumnOperations getColumns() {
		return columns;
	}

	public RowOperations getRows() {
		return rows;
	}

	public GroupByBuilder groupBy(final String column) throws IOException {

		IRowMapper mapper = new IRowMapper() {
			@Override
			public Object getKey(Row row) throws IOException {
				return row.getObject(column);
			}
		};

		return new GroupByBuilder(this, mapper, column);
	}

	public Table groupBy(final String column, IRowAggregator aggregator) throws IOException {

		return groupBy(new IRowMapper() {
			@Override
			public Object getKey(Row row) throws IOException {
				return row.getObject(column);
			}
		}, aggregator);

	}

	public Table binBy(final String column, double binSize, IRowAggregator aggregator) throws IOException {
		return groupBy(new BinRowMapper(column, binSize), aggregator);
	}

	public Table groupBy(IRowMapper mapper, IRowAggregator aggregator) throws IOException {
		RowGroupProcessor processor = new RowGroupProcessor(mapper);
		forEachRow(processor);
		Table result = null;
		Map<Object, List<Integer>> groups = processor.getGroups();
		for (Object key : groups.keySet()) {
			List<Integer> indices = groups.get(key);
			Table groupedTable = this.cloneStructure(name + ":" + key);
			for (Integer index : indices) {
				Row row = getRows().get(index);
				Row newRow = groupedTable.getRows().append();
				newRow.fill(row);
			}
			Table reducedTable = aggregator.aggregate(key, groupedTable);
			if (result == null) {
				result = reducedTable;
			} else {
				result.append(reducedTable);
			}
		}
		return result;
	}

	public List<Table> splitBy(IRowMapper mapper) throws IOException {
		RowGroupProcessor processor = new RowGroupProcessor(mapper);
		forEachRow(processor);
		List<Table> result = new Vector<>();
		Map<Object, List<Integer>> groups = processor.getGroups();
		for (Object key : groups.keySet()) {
			List<Integer> indices = groups.get(key);
			Table groupedTable = this.cloneStructure(name + ":" + key);
			for (Integer index : indices) {
				Row row = getRows().get(index);
				Row newRow = groupedTable.getRows().append();
				newRow.fill(row);
			}
			result.add(groupedTable);
		}
		return result;
	}

	public void append(Table table) throws IOException {
		table.forEachRow(new RowCopyProcessor(this));
	}

	public int getMissings() {
		int missings = 0;
		for (AbstractColumn column : storage) {
			missings += column.getMissings();
		}
		return missings;
	}

	public void fillMissings(Object value) {
		for (AbstractColumn column : storage) {
			column.fillMissings(value);
		}
	}

	public int getUniqueValues() {
		int uniques = 0;
		for (AbstractColumn column : storage) {
			uniques += column.getUniqueValues();
		}
		return uniques;
	}

	public void replaceValue(Object oldValue, Object newValue) throws IOException {
		replaceValue(new Object[] { oldValue }, new Object[] { newValue });
	}

	public void replaceValue(Object[] oldValues, Object[] newValues) throws IOException {
		for (AbstractColumn column : storage) {
			column.replaceValue(oldValues, newValues);
		}
	}

	public void merge(final Table table2, final String column) throws IOException {
		merge(table2, column, column);
	}

	public void merge(final Table table2, final String columnTable1, final String columnTable2) throws IOException {

		Table.log(this, "Merging with table " + table2.getName() + " on " + columnTable1 + "=" + columnTable2 + "...");

		final List<String> columnsTable2 = new Vector<String>();

		// add all columns from table expect join column
		for (int i = 0; i < table2.getColumns().getSize(); i++) {
			AbstractColumn columTable2 = table2.getColumns().get(i);
			if (!columTable2.getName().equals(columnTable2)) {
				AbstractColumn newColumn = columTable2.cloneStructure();
				columns.append(newColumn);
				columnsTable2.add(columTable2.getName());
			}
		}

		// create index on table2 for columns columnTable2
		TableIndex index = table2.createIndex(columnTable2);

		forEachRow(new IRowProcessor() {

			public void process(Row row) throws IOException {

				Object value = row.getObject(columnTable1);
				Row rowTable2 = index.getRow(value);
				if (rowTable2 != null) {
					for (String columnTable2 : columnsTable2) {
						Object valueTable2 = rowTable2.getObject(columnTable2);
						row.set(columnTable2, valueTable2);
						// getColumn(columnTable2).set(row.getIndex(), valueTable2);
					}

				} else {
					for (String columnTable2 : columnsTable2) {
						row.set(columnTable2, null);
						// getColumn(columnTable2).set(row.getIndex(), null);
					}
				}
			}
		});

		Table.log(this, "Merged tables. New size [" + getRows().getSize() + " x " + getColumns().getSize() + "]");

	}

	public Table cloneStructure(String name) throws IOException {
		Table table = new Table(getName() + ":" + name);
		for (AbstractColumn column : storage) {
			table.getColumns().append(column.cloneStructure());
		}
		return table;
	}

	public Table clone() {
		Table.log(this, "Cloing table...");
		Table table = null;
		try {
			table = cloneStructure("cloned");
			for (AbstractColumn column : storage) {
				table.getColumn(column.getName()).copyDataFrom(column);
			}
		} catch (IOException e) {
			e.printStackTrace();
		}
		Table.log(this, "Table cloned.");
		return table;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getName() {
		return name;
	}

	public TableIndex createIndex(String column) throws IOException {
		Table.log(this, "Creating index on column " + column + "...");
		assertsColumnExists(column);
		TableIndex index = new TableIndex(this);
		index.build(getColumn(column));
		Table.log(this, "Index created.");
		return index;
	}

	@Override
	public String toString() {
		try {
			return getAsString(0, 25);
		} catch (Exception e) {
			return "ERROR";
		}
	}

	protected void assertsColumnExists(String column) throws IOException {
		if (columns.get(column) == null) {
			throw new IOException("Column '" + column + "' not found.");
		}
	}

	protected void assertsColumnExists(int column) throws IOException {
		if (column >= getColumns().getSize()) {
			throw new IOException("Column '" + column + "' not found.");
		}
	}

	protected void assertsNotEmpty() throws IOException {
		if (storage.size() == 0) {
			throw new IOException("Table is empty.");
		}
	}

	public void printFirst(int n) throws IOException {
		n = Math.min(n, getRows().getSize());
		printBetween(0, n - 1);
	}

	public void printLast(int n) throws IOException {
		int height = getRows().getSize() - 1;
		int start = Math.max(height - n, 0);
		printBetween(start, height);
	}

	public void printBetween(int start, int end) throws IOException {
		System.out.println(getAsString(start, end));
	}

	public String getAsString(int start, int end) throws IOException {

		int height = getRows().getSize() - 1;
		start = Math.max(start, 0);
		end = Math.min(end, height);

		String[] columns = getColumns().getNames();
		String[] header = new String[columns.length + 1];
		header[0] = "";
		for (int i = 0; i < columns.length; i++) {
			header[i + 1] = columns[i];
		}

		String[][] data = getRows().data(start, end);
		String result = "";
		result += "\n" + name + ":\n";
		result += FlipTable.of(header, data);
		result += "Showing " + (start + 1) + " to " + (end + 1) + " of " + getRows().getSize() + " entries, "
				+ getColumns().getSize() + " total columns\n\n";
		return result;
	}

	public void print() throws IOException {
		printFirst(25);
	}

	public void printAll() throws IOException {
		printFirst(getRows().getSize());
	}

	public Table getSummary() throws IOException {
		Table table = new Table(name + ":summary");
		table.getColumns().append(new StringColumn("column"));
		table.getColumns().append(new StringColumn("type"));
		table.getColumns().append(new StringColumn("min"));
		table.getColumns().append(new StringColumn("mean"));
		table.getColumns().append(new StringColumn("max"));
		table.getColumns().append(new StringColumn("missings"));
		table.getColumns().append(new StringColumn("n"));
		for (AbstractColumn column : storage) {
			Row row = table.getRows().append();
			row.setString("column", column.getName());
			row.setString("type", column.getType());
			row.setString("min", column.getMin());
			row.setString("mean", column.getMean());
			row.setString("max", column.getMax());
			row.setString("missings", column.getMissings());
			row.setString("n", (column.getSize() - column.getMissings()));
		}
		return table;
	}

	public void printSummary() throws IOException {

		System.out.println(name + " [" + getRows().getSize() + " x " + getColumns().getSize() + "]");

		Table table = getSummary();
		table.printAll();
	}

	public void detectTypes() throws IOException {

		Table.log(this, "Detecting table types...");

		// try to find the right type
		for (int i = 0; i < getColumns().getSize(); i++) {
			AbstractColumn column = getColumns().get(i);
			ColumnType type = ColumnTypeDetector.guessType(column);
			if (type != column.getType()) {
				Table.log(this, "  Update type of " + column.getName() + " to " + type + "...");
				getColumns().setType(column, type);
			}
		}
		Table.log(this, "Types updated.");
	}

	public void clear() {
		storage.clear();
		columns.clear();
		rows.clear();
	}

	public static void log(Table table, String message) {
		if (logging) {
			log(table.getName() + ": " + message);
		}
	}

	public static void log(String message) {
		if (logging) {
			System.out.println(message);
		}
	}

	public static void disableLog() {
		logging = false;
	}

	public static void enableLog() {
		logging = true;
	}

}
