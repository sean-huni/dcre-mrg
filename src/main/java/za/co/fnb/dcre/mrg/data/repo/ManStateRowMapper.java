package za.co.fnb.dcre.mrg.data.repo;

import org.springframework.jdbc.core.RowMapper;
import za.co.fnb.dcre.mrg.data.model.ManStateRow;

import java.sql.ResultSet;
import java.sql.SQLException;

public class ManStateRowMapper implements RowMapper<ManStateRow> {

    @Override
    public ManStateRow mapRow(final ResultSet r, final int rowNum) throws SQLException {
        return new ManStateRow(r.getString("mandate_ref"), r.getString("state"));
    }
}
