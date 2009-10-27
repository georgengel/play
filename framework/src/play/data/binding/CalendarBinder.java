package play.data.binding;

import java.lang.annotation.Annotation;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;
import java.util.Date;

import org.apache.commons.lang.StringUtils;
import play.data.binding.annotations.AnnotationHelper;
import play.data.binding.annotations.As;
import play.i18n.Lang;
import play.libs.I18N;

/**
 * Binder that support Calendar class.
 */
public class CalendarBinder implements SupportedType<Calendar> {

    public Calendar bind(Annotation[] annotations, String value) throws Exception {
        Calendar cal;
        if (Lang.get() != null && !"".equals(Lang.get())) {
            cal = Calendar.getInstance(new Locale(Lang.get()));
        } else {
            cal = Calendar.getInstance(Locale.getDefault());
        }

        Date date = AnnotationHelper.getDateAs(annotations, value);
        if (date != null) {
            cal.setTime(date);
        } else {
            cal.setTime(new SimpleDateFormat(I18N.getDateFormat()).parse(value));
        }

        return cal;
    }
}
