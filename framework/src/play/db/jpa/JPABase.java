package play.db.jpa;

import java.io.Serializable;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.persistence.CascadeType;
import javax.persistence.EntityManager;
import javax.persistence.Id;
import javax.persistence.ManyToMany;
import javax.persistence.ManyToOne;
import javax.persistence.MappedSuperclass;
import javax.persistence.OneToMany;
import javax.persistence.OneToOne;
import javax.persistence.PersistenceException;
import org.hibernate.collection.PersistentCollection;
import org.hibernate.collection.PersistentMap;
import org.hibernate.exception.GenericJDBCException;
import org.hibernate.proxy.HibernateProxy;
import play.PlayPlugin;
import play.exceptions.UnexpectedException;

/**
 * A super class for JPA entities 
 */
@MappedSuperclass
public class JPABase implements Serializable, play.db.Model {

    public void _save() {
        if (!em().contains(this)) {
            em().persist(this);
            PlayPlugin.postEvent("JPASupport.objectPersisted", this);
        }
        avoidCascadeSaveLoops.set(new ArrayList<JPABase>());
        try {
            saveAndCascade(true);
        } finally {
            avoidCascadeSaveLoops.get().clear();
        }
        try {
            em().flush();
        } catch (PersistenceException e) {
            if (e.getCause() instanceof GenericJDBCException) {
                throw new PersistenceException(((GenericJDBCException) e.getCause()).getSQL());
            } else {
                throw e;
            }
        }
        avoidCascadeSaveLoops.set(new ArrayList<JPABase>());
        try {
            saveAndCascade(false);
        } finally {
            avoidCascadeSaveLoops.get().clear();
        }
    }

    public void _delete() {
        try {
            avoidCascadeSaveLoops.set(new ArrayList<JPABase>());
            try {
                saveAndCascade(true);
            } finally {
                avoidCascadeSaveLoops.get().clear();
            }
            em().remove(this);
            try {
                em().flush();
            } catch (PersistenceException e) {
                if (e.getCause() instanceof GenericJDBCException) {
                    throw new PersistenceException(((GenericJDBCException) e.getCause()).getSQL());
                } else {
                    throw e;
                }
            }
            avoidCascadeSaveLoops.set(new ArrayList<JPABase>());
            try {
                saveAndCascade(false);
            } finally {
                avoidCascadeSaveLoops.get().clear();
            }
            PlayPlugin.postEvent("JPASupport.objectDeleted", this);
        } catch (PersistenceException e) {
            throw e;
        } catch (Throwable e) {
            throw new RuntimeException(e);
        }
    }

    // ~~~ SAVING
    public transient boolean willBeSaved = false;
    static transient ThreadLocal<List<JPABase>> avoidCascadeSaveLoops = new ThreadLocal<List<JPABase>>();

    private void saveAndCascade(boolean willBeSaved) {
        this.willBeSaved = willBeSaved;
        if (avoidCascadeSaveLoops.get().contains(this)) {
            return;
        } else {
            avoidCascadeSaveLoops.get().add(this);
            if (willBeSaved) {
                PlayPlugin.postEvent("JPASupport.objectUpdated", this);
            }
        }
        // Cascade save
        try {
            Set<Field> fields = new HashSet<Field>();
            Class clazz = this.getClass();
            while (!clazz.equals(JPABase.class)) {
                Collections.addAll(fields, clazz.getDeclaredFields());
                clazz = clazz.getSuperclass();
            }
            for (Field field : fields) {
                field.setAccessible(true);
                if (Modifier.isTransient(field.getModifiers())) {
                    continue;
                }
                boolean doCascade = false;
                if (field.isAnnotationPresent(OneToOne.class)) {
                    doCascade = cascadeAll(field.getAnnotation(OneToOne.class).cascade());
                }
                if (field.isAnnotationPresent(OneToMany.class)) {
                    doCascade = cascadeAll(field.getAnnotation(OneToMany.class).cascade());
                }
                if (field.isAnnotationPresent(ManyToOne.class)) {
                    doCascade = cascadeAll(field.getAnnotation(ManyToOne.class).cascade());
                }
                if (field.isAnnotationPresent(ManyToMany.class)) {
                    doCascade = cascadeAll(field.getAnnotation(ManyToMany.class).cascade());
                }
                if (doCascade) {
                    Object value = field.get(this);
                    if (value == null) {
                        continue;
                    }
                    if (value instanceof PersistentMap) {
                        if (((PersistentMap) value).wasInitialized()) {
                            for (Object o : ((Map) value).values()) {
                                if (o instanceof JPABase) {
                                    ((JPABase) o).saveAndCascade(willBeSaved);
                                }
                            }
                        }
                        continue;
                    }
                    if (value instanceof PersistentCollection) {
                        if (((PersistentCollection) value).wasInitialized()) {
                            for (Object o : (Collection) value) {
                                if (o instanceof JPABase) {
                                    ((JPABase) o).saveAndCascade(willBeSaved);
                                }
                            }
                        }
                        continue;
                    }
                    if (value instanceof HibernateProxy && value instanceof JPABase) {
                        if (!((HibernateProxy) value).getHibernateLazyInitializer().isUninitialized()) {
                            ((JPABase) ((HibernateProxy) value).getHibernateLazyInitializer().getImplementation()).saveAndCascade(willBeSaved);
                        }
                        continue;
                    }
                    if (value instanceof JPABase) {
                        ((JPABase) value).saveAndCascade(willBeSaved);
                        continue;
                    }
                }
            }
        } catch (Exception e) {
            throw new UnexpectedException("During cascading save()", e);
        }
    }

    private static boolean cascadeAll(CascadeType[] types) {
        for (CascadeType cascadeType : types) {
            if (cascadeType == CascadeType.ALL || cascadeType == CascadeType.PERSIST) {
                return true;
            }
        }
        return false;
    }

    /**
     * Retrieve the current entityManager
     * @return the current entityManager
     */
    public static EntityManager em() {
        return JPA.em();
    }

    /**
     * JPASupport instances a and b are equals if either <strong>a == b</strong> or a and b have same </strong>{@link #key key} and class</strong>
     * @param other 
     * @return true if equality condition above is verified
     */
    @Override
    public boolean equals(Object other) {
        if (other == null) {
            return false;
        }
        if ((this == other)) {
            return true;
        }
        if (!(this.getClass().equals(other.getClass()))) {
            return false;
        }
        if (this.getEntityId() == null) {
            return false;
        }
        return this.getEntityId().equals(((JPABase) other).getEntityId());
    }

    @Override
    public int hashCode() {
        if (this.getEntityId() == null) {
            return 0;
        }
        return this.getEntityId().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "[" + getEntityId() + "]";
    }

    public static class JPAQueryException extends RuntimeException {

        public JPAQueryException(String message) {
            super(message);
        }

        public JPAQueryException(String message, Throwable e) {
            super(message + ": " + e.getMessage(), e);
        }

        public static Throwable findBestCause(Throwable e) {
            Throwable best = e;
            Throwable cause = e;
            int it = 0;
            while ((cause = cause.getCause()) != null && it++ < 10) {
                if (cause instanceof ClassCastException) {
                    best = cause;
                    break;
                }
                if (cause instanceof SQLException) {
                    best = cause;
                    break;
                }
            }
            return best;
        }
    }

    // File attachments
    void setupAttachment() {
        for (Field field : getClass().getDeclaredFields()) {
            if (FileAttachment.class.isAssignableFrom(field.getType())) {
                try {
                    field.setAccessible(true);
                    FileAttachment attachment = (FileAttachment) field.get(this);
                    if (attachment != null) {
                        attachment.model = this;
                        attachment.name = field.getName();
                    } else {
                        attachment = new FileAttachment();
                        attachment.model = this;
                        attachment.name = field.getName();
                        field.set(this, attachment);
                    }
                } catch (Exception ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
    }

    void saveAttachment() {
        for (Field field : getClass().getDeclaredFields()) {
            if (field.getType().equals(FileAttachment.class)) {
                try {
                    field.setAccessible(true);
                    FileAttachment attachment = (FileAttachment) field.get(this);
                    if (attachment != null) {
                        attachment.model = this;
                        attachment.name = field.getName();
                        attachment.save();
                    }
                } catch (Exception ex) {
                    throw new UnexpectedException(ex);
                }
            }
        }
    }

    // More utils
    public static Object findKey(Object entity) {
        try {
            Class c = entity.getClass();
            while (!c.equals(Object.class)) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Id.class)) {
                        field.setAccessible(true);
                        return field.get(entity);
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Exception e) {
            throw new UnexpectedException("Error while determining the object @Id for an object of type " + entity.getClass());
        }
        return null;
    }

    public Object _getKey() {
        return findKey(this);
    }

    public static Class findKeyType(Class c) {
        try {
            while (!c.equals(Object.class)) {
                for (Field field : c.getDeclaredFields()) {
                    if (field.isAnnotationPresent(Id.class)) {
                        field.setAccessible(true);
                        return field.getType();
                    }
                }
                c = c.getSuperclass();
            }
        } catch (Exception e) {
            throw new UnexpectedException("Error while determining the object @Id for an object of type " + c);
        }
        return null;
    }

    public Class _getKeyType() {
        return findKeyType(this.getClass());
    }

    private transient Object key;

    public Object getEntityId() {
        if (key == null) {
            key = findKey(this);
        }
        return key;
    }

    public void _loader() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

    public List<Property> _properties() {
        throw new UnsupportedOperationException("Not supported yet.");
    }

}